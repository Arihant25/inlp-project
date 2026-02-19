//! Variation 2: The Fat Model / ActiveRecord Purist
//! This approach places business and data access logic directly onto the model structs.
//! It leverages SeaORM's ActiveRecord features, leading to more concise handlers
//! and an object-oriented feel. Good for rapid development and less complex domains.

use actix_web::{web, App, HttpServer, Responder, HttpResponse, ResponseError};
use sea_orm::{prelude::*, sea_query::OnConflict, ActiveValue, Database, DatabaseConnection, DbErr, EntityTrait, TransactionTrait};
use sea_orm_migration::prelude::*;
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use futures::executor::block_on;

// --- 1. Error Handling ---
#[derive(Debug, thiserror::Error)]
enum AppError {
    #[error("Database error: {0}")]
    Database(#[from] DbErr),
    #[error("Not found: {0}")]
    NotFound(String),
    #[error("Invalid input: {0}")]
    BadInput(String),
}

impl ResponseError for AppError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            AppError::Database(_) => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
            AppError::NotFound(_) => actix_web::http::StatusCode::NOT_FOUND,
            AppError::BadInput(_) => actix_web::http::StatusCode::BAD_REQUEST,
        }
    }
}

// --- 2. Models with ActiveRecord Logic (models/mod.rs) ---
mod models {
    use super::AppError;
    use sea_orm::{entity::prelude::*, ActiveValue, ColumnTrait, DbConn, DbErr, EntityTrait, QueryFilter, TransactionTrait};
    use serde::{Deserialize, Serialize};

    // --- User Entity ---
    #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
    #[sea_orm(table_name = "users")]
    pub struct Model {
        #[sea_orm(primary_key, auto_increment = false)]
        pub id: Uuid,
        #[sea_orm(unique)]
        pub email: String,
        pub password_hash: String,
        pub is_active: bool,
        pub created_at: ChronoDateTimeUtc,
    }

    #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
    pub enum Relation {
        #[sea_orm(has_many = "super::post::Entity")]
        Post,
        #[sea_orm(has_many = "super::user_role::Entity")]
        UserRole,
    }

    impl Related<super::post::Entity> for Entity {
        fn to() -> RelationDef { Relation::Post.def() }
    }
    
    impl Related<super::role::Entity> for Entity {
        fn to() -> RelationDef { Relation::UserRole.def() }
        fn via() -> Option<RelationDef> { Some(super::user_role::Relation::Role.def().rev()) }
    }

    #[derive(Deserialize)]
    pub struct CreateUserPayload {
        pub email: String,
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct UserQuery {
        pub is_active: Option<bool>,
    }

    // ActiveRecord-style implementation
    impl Model {
        // Transactional create method
        pub async fn create_with_default_role(db: &DbConn, payload: CreateUserPayload) -> Result<Self, AppError> {
            let txn = db.begin().await?;

            if Entity::find().filter(Column::Email.eq(&payload.email)).one(&txn).await?.is_some() {
                return Err(AppError::BadInput("Email already in use".to_string()));
            }

            let default_role = super::role::Entity::find()
                .filter(super::role::Column::Name.eq("USER"))
                .one(&txn).await?
                .ok_or_else(|| AppError::NotFound("Default role 'USER' not found".to_string()))?;

            let new_user = ActiveModel {
                id: ActiveValue::Set(Uuid::new_v4()),
                email: ActiveValue::Set(payload.email),
                password_hash: ActiveValue::Set("...hashed...".to_string()),
                is_active: ActiveValue::Set(true),
                created_at: ActiveValue::Set(chrono::Utc::now()),
            };
            let user = new_user.insert(&txn).await?;

            let user_role_link = super::user_role::ActiveModel {
                user_id: ActiveValue::Set(user.id),
                role_id: ActiveValue::Set(default_role.id),
            };
            user_role_link.insert(&txn).await?;

            txn.commit().await?;
            Ok(user)
        }

        pub async fn find_with_filters(db: &DbConn, filter: UserQuery) -> Result<Vec<Self>, DbErr> {
            let mut query = Entity::find();
            if let Some(active_status) = filter.is_active {
                query = query.filter(Column::IsActive.eq(active_status));
            }
            query.all(db).await
        }

        pub async fn get_posts(&self, db: &DbConn) -> Result<Vec<super::post::Model>, DbErr> {
            self.find_related(super::post::Entity).all(db).await
        }

        pub async fn assign_role(&self, db: &DbConn, role_name: &str) -> Result<(), AppError> {
            let role_to_assign = super::role::Entity::find()
                .filter(super::role::Column::Name.eq(role_name))
                .one(db).await?
                .ok_or_else(|| AppError::NotFound(format!("Role '{}' not found", role_name)))?;
            
            self.find_related(super::role::Entity)
                .via(super::user_role::Entity)
                .link(db, &role_to_assign)
                .await?;

            Ok(())
        }
    }
    impl ActiveModelBehavior for ActiveModel {}

    // --- Post Entity ---
    pub mod post {
        use super::Model as UserModel;
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};

        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "posts")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)]
            pub id: Uuid,
            pub user_id: Uuid,
            pub title: String,
            #[sea_orm(column_type = "Text")]
            pub content: String,
            pub status: PostStatus,
        }

        #[derive(Debug, Clone, PartialEq, Eq, EnumIter, DeriveActiveEnum, Serialize, Deserialize)]
        #[sea_orm(rs_type = "String", db_type = "String(Some(10))")]
        pub enum PostStatus { #[sea_orm(string_value = "DRAFT")] Draft, #[sea_orm(string_value = "PUBLISHED")] Published }

        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(belongs_to = "super::Entity", from = "Column::UserId", to = "super::Column::Id")]
            User,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }

    // --- Role Entity ---
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
        pub enum Relation {
            #[sea_orm(has_many = "super::user_role::Entity")]
            UserRole,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }

    // --- UserRole Join Entity ---
    pub mod user_role {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};

        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "user_roles")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)]
            pub user_id: Uuid,
            #[sea_orm(primary_key, auto_increment = false)]
            pub role_id: Uuid,
        }

        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(belongs_to = "super::Entity", from = "Column::UserId", to = "super::Column::Id")]
            User,
            #[sea_orm(belongs_to = "super::role::Entity", from = "Column::RoleId", to = "super::role::Column::Id")]
            Role,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }
}

// --- 3. Handlers (handlers.rs) ---
mod handlers {
    use super::models::{self, CreateUserPayload, UserQuery};
    use super::AppError;
    use actix_web::{web, HttpResponse, Responder};
    use sea_orm::{DatabaseConnection, EntityTrait};
    use serde::Deserialize;
    use uuid::Uuid;

    #[derive(Deserialize)]
    pub struct AssignRolePayload {
        pub role_name: String,
    }

    pub async fn create_user(
        db: web::Data<DatabaseConnection>,
        payload: web::Json<CreateUserPayload>,
    ) -> Result<impl Responder, AppError> {
        let user = models::Model::create_with_default_role(&db, payload.into_inner()).await?;
        Ok(HttpResponse::Created().json(user))
    }

    pub async fn list_users(
        db: web::Data<DatabaseConnection>,
        query: web::Query<UserQuery>,
    ) -> Result<impl Responder, AppError> {
        let users = models::Model::find_with_filters(&db, query.into_inner()).await?;
        Ok(HttpResponse::Ok().json(users))
    }

    pub async fn list_user_posts(
        db: web::Data<DatabaseConnection>,
        path: web::Path<Uuid>,
    ) -> Result<impl Responder, AppError> {
        let user_id = path.into_inner();
        let user = models::Entity::find_by_id(user_id).one(&**db).await?
            .ok_or_else(|| AppError::NotFound(format!("User {} not found", user_id)))?;
        
        let posts = user.get_posts(&db).await?;
        Ok(HttpResponse::Ok().json(posts))
    }

    pub async fn assign_role(
        db: web::Data<DatabaseConnection>,
        path: web::Path<Uuid>,
        payload: web::Json<AssignRolePayload>,
    ) -> Result<impl Responder, AppError> {
        let user_id = path.into_inner();
        let user = models::Entity::find_by_id(user_id).one(&**db).await?
            .ok_or_else(|| AppError::NotFound(format!("User {} not found", user_id)))?;

        user.assign_role(&db, &payload.role_name).await?;
        Ok(HttpResponse::Ok().json({"status": "success"}))
    }
}

// --- 4. Database Migrations (migrator.rs) ---
// This part is identical in function to Variation 1, as migrations are declarative.
mod migrator {
    use sea_orm::{prelude::Uuid, sea_query::Table, ConnectionTrait, DbErr, Statement};
    use sea_orm_migration::prelude::*;
    use super::models::{self, post, role, user_role};

    pub struct Migrator;

    #[async_trait::async_trait]
    impl MigratorTrait for Migrator {
        fn migrations() -> Vec<Box<dyn MigrationTrait>> { vec![Box::new(Migration)] }
    }

    struct Migration;

    #[async_trait::async_trait]
    impl MigrationTrait for Migration {
        async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager.create_table(Table::create().table(models::Entity).if_not_exists()
                .col(ColumnDef::new(models::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(models::Column::Email).string().not_null().unique_key())
                .col(ColumnDef::new(models::Column::PasswordHash).string().not_null())
                .col(ColumnDef::new(models::Column::IsActive).boolean().not_null())
                .col(ColumnDef::new(models::Column::CreatedAt).timestamp_with_time_zone().not_null())
                .to_owned()).await?;

            manager.create_table(Table::create().table(post::Entity).if_not_exists()
                .col(ColumnDef::new(post::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(post::Column::UserId).uuid().not_null())
                .col(ColumnDef::new(post::Column::Title).string().not_null())
                .col(ColumnDef::new(post::Column::Content).text().not_null())
                .col(ColumnDef::new(post::Column::Status).string().not_null())
                .foreign_key(ForeignKey::create().name("fk-post-user_id").from(post::Entity, post::Column::UserId).to(models::Entity, models::Column::Id).on_delete(ForeignKeyAction::Cascade))
                .to_owned()).await?;

            manager.create_table(Table::create().table(role::Entity).if_not_exists()
                .col(ColumnDef::new(role::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(role::Column::Name).string().not_null().unique_key())
                .to_owned()).await?;

            manager.create_table(Table::create().table(user_role::Entity).if_not_exists()
                .col(ColumnDef::new(user_role::Column::UserId).uuid().not_null())
                .col(ColumnDef::new(user_role::Column::RoleId).uuid().not_null())
                .primary_key(Index::create().col(user_role::Column::UserId).col(user_role::Column::RoleId))
                .foreign_key(ForeignKey::create().name("fk-user_role-user_id").from(user_role::Entity, user_role::Column::UserId).to(models::Entity, models::Column::Id).on_delete(ForeignKeyAction::Cascade))
                .foreign_key(ForeignKey::create().name("fk-user_role-role_id").from(user_role::Entity, user_role::Column::RoleId).to(role::Entity, role::Column::Id).on_delete(ForeignKeyAction::Cascade))
                .to_owned()).await?;

            let db = manager.get_connection();
            db.execute(Statement::from_sql_and_values(
                manager.get_database_backend(),
                r#"INSERT INTO "roles" ("id", "name") VALUES ($1, 'ADMIN'), ($2, 'USER') ON CONFLICT DO NOTHING"#,
                [Uuid::new_v4().into(), Uuid::new_v4().into()],
            )).await?;
            Ok(())
        }
    }
}

// --- 5. Main Application Setup ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db_conn = block_on(async {
        let db = Database::connect("sqlite::memory:").await.unwrap();
        migrator::Migrator::up(&db, None).await.unwrap();
        db
    });

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(db_conn.clone()))
            .service(
                web::scope("/users")
                    .route("", web::post().to(handlers::create_user))
                    .route("", web::get().to(handlers::list_users))
                    .route("/{user_id}/posts", web::get().to(handlers::list_user_posts))
                    .route("/{user_id}/roles", web::post().to(handlers::assign_role))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}