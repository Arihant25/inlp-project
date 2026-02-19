// Variation 3: Active Record Pattern with SeaORM
// This variation uses the SeaORM library, which provides a more traditional
// ORM and Active Record experience, similar to Rails or Django. Models are
// "active" and contain methods for database operations. This reduces the need
// for a separate repository layer and can make CRUD operations very concise.

// --- Cargo.toml dependencies ---
/*
[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
sea-orm = { version = "0.12", features = [ "sqlx-sqlite", "runtime-tokio-rustls", "macros" ] }
uuid = { version = "1", features = ["v4", "serde"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
anyhow = "1"
*/

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use sea_orm::{prelude::*, sea_query::Table, ConnectionTrait, Database, DatabaseConnection, DbErr, EntityTrait, Schema, TransactionTrait};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;

// --- Entity Definitions (like models) ---
mod entities {
    use sea_orm::entity::prelude::*;
    use serde::{Deserialize, Serialize};

    // --- User Entity ---
    #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
    #[sea_orm(table_name = "users")]
    pub struct Model {
        #[sea_orm(primary_key, auto_increment = false)]
        pub id: Uuid,
        #[sea_orm(unique)]
        pub email: String,
        #[serde(skip)]
        pub password_hash: String,
        pub role: RoleEnum,
        pub is_active: bool,
        pub created_at: ChronoDateTimeUtc,
    }

    #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
    pub enum Relation {
        #[sea_orm(has_many = "super::post::Entity")]
        Post,
    }

    impl Related<super::post::Entity> for Entity {
        fn to() -> RelationDef {
            Relation::Post.def()
        }
    }
    
    impl Related<super::role::Entity> for Entity {
        fn to() -> RelationDef {
            super::user_role::Relation::Role.def()
        }
        fn via() -> Option<RelationDef> {
            Some(super::user_role::Relation::User.def().rev())
        }
    }

    #[derive(Debug, Clone, PartialEq, Eq, EnumIter, DeriveActiveEnum, Serialize, Deserialize, Copy)]
    #[sea_orm(rs_type = "String", db_type = "Text")]
    pub enum RoleEnum {
        #[sea_orm(string_value = "ADMIN")]
        Admin,
        #[sea_orm(string_value = "USER")]
        User,
    }

    impl ActiveModelBehavior for ActiveModel {}

    // --- Post Entity ---
    pub mod post {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};
        use super::{user, Status};

        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "posts")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)]
            pub id: Uuid,
            pub user_id: Uuid,
            pub title: String,
            #[sea_orm(column_type="Text")]
            pub content: String,
            pub status: Status,
        }

        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(
                belongs_to = "user::Entity",
                from = "Column::UserId",
                to = "user::Column::Id"
            )]
            User,
        }

        impl Related<user::Entity> for Entity {
            fn to() -> RelationDef {
                Relation::User.def()
            }
        }

        #[derive(Debug, Clone, PartialEq, Eq, EnumIter, DeriveActiveEnum, Serialize, Deserialize, Copy)]
        #[sea_orm(rs_type = "String", db_type = "Text")]
        pub enum Status {
            #[sea_orm(string_value = "DRAFT")]
            Draft,
            #[sea_orm(string_value = "PUBLISHED")]
            Published,
        }

        impl ActiveModelBehavior for ActiveModel {}
    }

    // --- Role and UserRole for Many-to-Many ---
    pub mod role {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};

        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "roles")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)]
            pub id: Uuid,
            #[sea_orm(unique)]
            pub name: String,
        }

        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {}
        impl ActiveModelBehavior for ActiveModel {}
    }

    pub mod user_role {
        use sea_orm::entity::prelude::*;
        use super::{user, role};

        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel)]
        #[sea_orm(table_name = "user_roles")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)]
            pub user_id: Uuid,
            #[sea_orm(primary_key, auto_increment = false)]
            pub role_id: Uuid,
        }

        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(belongs_to = "user::Entity", from = "Column::UserId", to = "user::Column::Id")]
            User,
            #[sea_orm(belongs_to = "role::Entity", from = "Column::RoleId", to = "role::Column::Id")]
            Role,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }
}

// --- Database Migrator ---
mod migrator {
    use super::entities::{post, role, user, user_role};
    use sea_orm::{prelude::*, sea_query::*, ConnectionTrait, Schema};

    pub async fn run_migrations(db: &DatabaseConnection) -> Result<(), DbErr> {
        let schema = Schema::new(db.get_database_backend());
        
        db.execute(db.get_database_backend().build(&schema.create_table_from_entity(user::Entity)).into()).await?;
        db.execute(db.get_database_backend().build(&schema.create_table_from_entity(post::Entity)).into()).await?;
        db.execute(db.get_database_backend().build(&schema.create_table_from_entity(role::Entity)).into()).await?;
        db.execute(db.get_database_backend().build(&schema.create_table_from_entity(user_role::Entity)).into()).await?;
        
        Ok(())
    }
}

// --- API Handlers ---
mod handlers {
    use super::entities::{post, role, user, user_role, RoleEnum, Status};
    use super::AppError;
    use axum::{
        extract::{Path, Query, State},
        http::StatusCode,
        Json,
    };
    use sea_orm::{prelude::*, ActiveModelTrait, ColumnTrait, EntityTrait, QueryFilter, Set, TransactionTrait};
    use serde::{Deserialize, Serialize};

    #[derive(Deserialize)]
    pub struct CreateUserPayload {
        email: String,
        password: String,
    }

    pub async fn create_user(
        State(db): State<DatabaseConnection>,
        Json(payload): Json<CreateUserPayload>,
    ) -> Result<(StatusCode, Json<user::Model>), AppError> {
        let new_user = user::ActiveModel {
            id: Set(Uuid::new_v4()),
            email: Set(payload.email),
            password_hash: Set(format!("hashed:{}", payload.password)),
            role: Set(RoleEnum::User),
            is_active: Set(true),
            ..Default::default()
        };
        let user = new_user.insert(&db).await?;
        Ok((StatusCode::CREATED, Json(user)))
    }

    #[derive(Deserialize)]
    pub struct UserFilters {
        is_active: Option<bool>,
    }

    pub async fn list_users(
        State(db): State<DatabaseConnection>,
        Query(filters): Query<UserFilters>,
    ) -> Result<Json<Vec<user::Model>>, AppError> {
        let mut query = user::Entity::find();
        if let Some(is_active) = filters.is_active {
            query = query.filter(user::Column::IsActive.eq(is_active));
        }
        let users = query.all(&db).await?;
        Ok(Json(users))
    }

    #[derive(Serialize)]
    pub struct UserWithPostsAndRoles {
        #[serde(flatten)]
        user: user::Model,
        posts: Vec<post::Model>,
        roles: Vec<role::Model>,
    }

    pub async fn get_user_details(
        State(db): State<DatabaseConnection>,
        Path(user_id): Path<Uuid>,
    ) -> Result<Json<UserWithPostsAndRoles>, AppError> {
        let user = user::Entity::find_by_id(user_id).one(&db).await?.ok_or(AppError(anyhow::anyhow!("User not found")))?;
        let posts = user.find_related(post::Entity).all(&db).await?;
        let roles = user.find_related(role::Entity).all(&db).await?;
        
        Ok(Json(UserWithPostsAndRoles { user, posts, roles }))
    }

    // Transactional operation
    pub async fn create_user_with_post_and_role(
        State(db): State<DatabaseConnection>,
    ) -> Result<StatusCode, AppError> {
        db.transaction::<_, (), DbErr>(|txn| {
            Box::pin(async move {
                // 1. Create Role if not exists
                let admin_role = role::ActiveModel {
                    id: Set(Uuid::new_v4()),
                    name: Set("ADMIN".to_owned()),
                };
                let admin_role = admin_role.insert(txn).await?;

                // 2. Create User
                let new_user = user::ActiveModel {
                    id: Set(Uuid::new_v4()),
                    email: Set("transactional_user@test.com".to_owned()),
                    password_hash: Set("hashed_pass".to_owned()),
                    role: Set(RoleEnum::Admin),
                    is_active: Set(true),
                    ..Default::default()
                };
                let user = new_user.insert(txn).await?;

                // 3. Create Post for User
                let new_post = post::ActiveModel {
                    id: Set(Uuid::new_v4()),
                    user_id: Set(user.id),
                    title: Set("My First Transactional Post".to_owned()),
                    content: Set("This was created in a transaction.".to_owned()),
                    status: Set(Status::Published),
                };
                new_post.insert(txn).await?;

                // 4. Link User and Role (Many-to-Many)
                let user_role_link = user_role::ActiveModel {
                    user_id: Set(user.id),
                    role_id: Set(admin_role.id),
                };
                user_role_link.insert(txn).await?;

                Ok(())
            })
        })
        .await
        .map_err(|e| AppError(e.into()))?;

        Ok(StatusCode::CREATED)
    }
}

// --- App State and Error Handling ---
#[derive(Clone)]
struct AppState {
    db: DatabaseConnection,
}

struct AppError(anyhow::Error);
impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        tracing::error!("Application error: {:#}", self.0);
        (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error").into_response()
    }
}
impl<E> From<E> for AppError where E: Into<anyhow::Error> {
    fn from(err: E) -> Self { Self(err.into()) }
}

// --- Main Entry Point ---
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_max_level(tracing::Level::INFO).init();

    let db = Database::connect("sqlite::memory:").await?;
    migrator::run_migrations(&db).await?;

    let app = Router::new()
        .route("/users", get(handlers::list_users).post(handlers::create_user))
        .route("/users/:id", get(handlers::get_user_details))
        .route("/users/transactional_create", post(handlers::create_user_with_post_and_role))
        .with_state(db);

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::info!("listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();

    Ok(())
}