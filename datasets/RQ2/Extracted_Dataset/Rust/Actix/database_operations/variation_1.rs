//! Variation 1: The Service-Repository Pattern Developer
//! This approach emphasizes a clear separation of concerns with distinct layers for
//! handlers (API), services (business logic), and repositories (data access).
//! It's robust, testable, and scales well for large applications.

use actix_web::{web, App, HttpServer, Responder, HttpResponse, ResponseError};
use sea_orm::{prelude::*, sea_query::OnConflict, ActiveValue, Database, DatabaseConnection, DbErr, EntityTrait, TransactionTrait};
use sea_orm_migration::prelude::*;
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use std::sync::Arc;
use futures::executor::block_on;

// --- 1. Error Handling ---
#[derive(Debug, thiserror::Error)]
enum ApiError {
    #[error("Database error: {0}")]
    DbError(#[from] DbErr),
    #[error("Not found: {0}")]
    NotFound(String),
    #[error("Bad request: {0}")]
    BadRequest(String),
}

impl ResponseError for ApiError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            ApiError::DbError(_) => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::NotFound(_) => actix_web::http::StatusCode::NOT_FOUND,
            ApiError::BadRequest(_) => actix_web::http::StatusCode::BAD_REQUEST,
        }
    }
}

// --- 2. Models & DTOs (models/mod.rs, models/dtos.rs) ---
mod models {
    pub mod user {
        use super::post;
        use super::role;
        use super::user_role;
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};

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
            #[sea_orm(has_many = "post::Entity")]
            Post,
            #[sea_orm(has_many = "user_role::Entity")]
            UserRole,
        }

        impl Related<post::Entity> for Entity {
            fn to() -> RelationDef { Relation::Post.def() }
        }

        impl Related<role::Entity> for Entity {
            fn to() -> RelationDef {
                Relation::UserRole.def()
            }
            fn via() -> Option<RelationDef> {
                Some(user_role::Relation::Role.def().rev())
            }
        }

        impl ActiveModelBehavior for ActiveModel {}
    }

    pub mod post {
        use super::user;
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
        pub enum PostStatus {
            #[sea_orm(string_value = "DRAFT")]
            Draft,
            #[sea_orm(string_value = "PUBLISHED")]
            Published,
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

        impl ActiveModelBehavior for ActiveModel {}
    }
    
    pub mod role {
        use super::user;
        use super::user_role;
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
             #[sea_orm(has_many = "user_role::Entity")]
            UserRole,
        }
        
        impl Related<user::Entity> for Entity {
            fn to() -> RelationDef {
                Relation::UserRole.def()
            }
            fn via() -> Option<RelationDef> {
                Some(user_role::Relation::User.def().rev())
            }
        }

        impl ActiveModelBehavior for ActiveModel {}
    }

    pub mod user_role {
        use super::{role, user};
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
            #[sea_orm(
                belongs_to = "user::Entity",
                from = "Column::UserId",
                to = "user::Column::Id"
            )]
            User,
            #[sea_orm(
                belongs_to = "role::Entity",
                from = "Column::RoleId",
                to = "role::Column::Id"
            )]
            Role,
        }

        impl ActiveModelBehavior for ActiveModel {}
    }

    pub mod dtos {
        use serde::Deserialize;
        use uuid::Uuid;

        #[derive(Deserialize)]
        pub struct CreateUserDto {
            pub email: String,
            pub password: String,
        }

        #[derive(Deserialize)]
        pub struct UserFilterDto {
            pub is_active: Option<bool>,
        }

        #[derive(Deserialize)]
        pub struct AssignRoleDto {
            pub role_name: String,
        }
    }
}

// --- 3. Repository Layer (repositories/user_repository.rs) ---
mod repositories {
    use super::models::{user, role, user_role, dtos::UserFilterDto};
    use sea_orm::{prelude::*, ActiveValue, ColumnTrait, Condition, DbConn, DbErr, EntityTrait, QueryFilter, QuerySelect};

    pub struct UserRepository;

    impl UserRepository {
        pub async fn find_by_id(db: &DbConn, id: Uuid) -> Result<Option<user::Model>, DbErr> {
            user::Entity::find_by_id(id).one(db).await
        }

        pub async fn find_by_email(db: &DbConn, email: &str) -> Result<Option<user::Model>, DbErr> {
            user::Entity::find().filter(user::Column::Email.eq(email)).one(db).await
        }

        pub async fn find_all_with_filter(db: &DbConn, filter: UserFilterDto) -> Result<Vec<user::Model>, DbErr> {
            let mut select = user::Entity::find();
            if let Some(is_active) = filter.is_active {
                select = select.filter(user::Column::IsActive.eq(is_active));
            }
            select.all(db).await
        }

        pub async fn save(db: &DbConn, user_model: user::ActiveModel) -> Result<user::Model, DbErr> {
            user_model.insert(db).await
        }
    }

    pub struct RoleRepository;

    impl RoleRepository {
        pub async fn find_by_name(db: &DbConn, name: &str) -> Result<Option<role::Model>, DbErr> {
            role::Entity::find().filter(role::Column::Name.eq(name)).one(db).await
        }
    }

    pub struct UserRoleRepository;

    impl UserRoleRepository {
        pub async fn assign_role_to_user(txn: &DatabaseTransaction, user_id: Uuid, role_id: Uuid) -> Result<(), DbErr> {
            let user_role = user_role::ActiveModel {
                user_id: ActiveValue::Set(user_id),
                role_id: ActiveValue::Set(role_id),
            };
            user_role::Entity::insert(user_role).exec(txn).await?;
            Ok(())
        }
    }
}

// --- 4. Service Layer (services/user_service.rs) ---
mod services {
    use super::models::{dtos::CreateUserDto, user, role};
    use super::repositories::{UserRepository, RoleRepository, UserRoleRepository};
    use super::ApiError;
    use sea_orm::{prelude::*, ActiveValue, DatabaseConnection, TransactionTrait};

    pub struct UserService {
        db: Arc<DatabaseConnection>,
    }

    impl UserService {
        pub fn new(db: Arc<DatabaseConnection>) -> Self {
            Self { db }
        }

        // Demonstrates Transaction and Rollback
        pub async fn create_user_with_default_role(&self, user_data: CreateUserDto) -> Result<user::Model, ApiError> {
            let txn = self.db.begin().await?;

            // Check if user exists
            if UserRepository::find_by_email(&txn, &user_data.email).await?.is_some() {
                return Err(ApiError::BadRequest("Email already exists".to_string()));
            }

            // Find default role
            let user_role = RoleRepository::find_by_name(&txn, "USER").await?
                .ok_or_else(|| ApiError::NotFound("Default role 'USER' not found".to_string()))?;

            // Create user
            let new_user = user::ActiveModel {
                id: ActiveValue::Set(Uuid::new_v4()),
                email: ActiveValue::Set(user_data.email),
                password_hash: ActiveValue::Set("...hashed_password...".to_string()), // Hashing omitted for brevity
                is_active: ActiveValue::Set(true),
                created_at: ActiveValue::Set(chrono::Utc::now()),
            };
            let user = new_user.insert(&txn).await?;

            // Assign role
            UserRoleRepository::assign_role_to_user(&txn, user.id, user_role.id).await?;

            txn.commit().await?;
            Ok(user)
        }

        pub async fn find_user_posts(&self, user_id: Uuid) -> Result<Vec<super::models::post::Model>, ApiError> {
            let user = UserRepository::find_by_id(&*self.db, user_id).await?
                .ok_or_else(|| ApiError::NotFound(format!("User with id {} not found", user_id)))?;
            
            let posts = user.find_related(super::models::post::Entity).all(&*self.db).await?;
            Ok(posts)
        }
    }
}

// --- 5. Handler Layer (handlers/user_handler.rs) ---
mod handlers {
    use super::models::dtos::{CreateUserDto, UserFilterDto, AssignRoleDto};
    use super::services::UserService;
    use super::ApiError;
    use super::repositories::{UserRepository, RoleRepository};
    use actix_web::{web, HttpResponse, Responder};
    use sea_orm::{DatabaseConnection, EntityTrait, ModelTrait};
    use std::sync::Arc;
    use uuid::Uuid;

    pub async fn create_user(
        user_service: web::Data<UserService>,
        user_data: web::Json<CreateUserDto>,
    ) -> Result<impl Responder, ApiError> {
        let user = user_service.create_user_with_default_role(user_data.into_inner()).await?;
        Ok(HttpResponse::Created().json(user))
    }

    pub async fn get_users(
        db: web::Data<Arc<DatabaseConnection>>,
        query: web::Query<UserFilterDto>,
    ) -> Result<impl Responder, ApiError> {
        let users = UserRepository::find_all_with_filter(&db, query.into_inner()).await?;
        Ok(HttpResponse::Ok().json(users))
    }

    pub async fn get_user_posts(
        user_service: web::Data<UserService>,
        path: web::Path<Uuid>,
    ) -> Result<impl Responder, ApiError> {
        let user_id = path.into_inner();
        let posts = user_service.find_user_posts(user_id).await?;
        Ok(HttpResponse::Ok().json(posts))
    }

    pub async fn assign_role_to_user(
        db: web::Data<Arc<DatabaseConnection>>,
        path: web::Path<Uuid>,
        role_data: web::Json<AssignRoleDto>,
    ) -> Result<impl Responder, ApiError> {
        let user_id = path.into_inner();
        let db = db.get_ref();

        let user = UserRepository::find_by_id(db, user_id).await?
            .ok_or_else(|| ApiError::NotFound(format!("User {} not found", user_id)))?;
        
        let role = RoleRepository::find_by_name(db, &role_data.role_name).await?
            .ok_or_else(|| ApiError::NotFound(format!("Role {} not found", role_data.role_name)))?;

        user.find_related(super::models::role::Entity)
            .via(super::models::user_role::Entity)
            .link(db, &role)
            .await?;

        Ok(HttpResponse::Ok().finish())
    }
}

// --- 6. Database Migrations (db/migrator.rs) ---
mod migrator {
    use sea_orm::{prelude::Uuid, sea_query::Table, ConnectionTrait, DbErr, Statement};
    use sea_orm_migration::prelude::*;
    use super::models::{user, post, role, user_role};

    pub struct Migrator;

    #[async_trait::async_trait]
    impl MigratorTrait for Migrator {
        fn migrations() -> Vec<Box<dyn MigrationTrait>> {
            vec![Box::new(InitialMigration)]
        }
    }

    struct InitialMigration;

    #[async_trait::async_trait]
    impl MigrationTrait for InitialMigration {
        async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager.create_table(
                Table::create()
                    .table(user::Entity)
                    .if_not_exists()
                    .col(ColumnDef::new(user::Column::Id).uuid().not_null().primary_key())
                    .col(ColumnDef::new(user::Column::Email).string().not_null().unique_key())
                    .col(ColumnDef::new(user::Column::PasswordHash).string().not_null())
                    .col(ColumnDef::new(user::Column::IsActive).boolean().not_null())
                    .col(ColumnDef::new(user::Column::CreatedAt).timestamp_with_time_zone().not_null())
                    .to_owned(),
            ).await?;

            manager.create_table(
                Table::create()
                    .table(post::Entity)
                    .if_not_exists()
                    .col(ColumnDef::new(post::Column::Id).uuid().not_null().primary_key())
                    .col(ColumnDef::new(post::Column::UserId).uuid().not_null())
                    .col(ColumnDef::new(post::Column::Title).string().not_null())
                    .col(ColumnDef::new(post::Column::Content).text().not_null())
                    .col(ColumnDef::new(post::Column::Status).string().not_null())
                    .foreign_key(
                        ForeignKey::create()
                            .name("fk-post-user_id")
                            .from(post::Entity, post::Column::UserId)
                            .to(user::Entity, user::Column::Id)
                            .on_delete(ForeignKeyAction::Cascade),
                    )
                    .to_owned(),
            ).await?;

            manager.create_table(
                Table::create()
                    .table(role::Entity)
                    .if_not_exists()
                    .col(ColumnDef::new(role::Column::Id).uuid().not_null().primary_key())
                    .col(ColumnDef::new(role::Column::Name).string().not_null().unique_key())
                    .to_owned(),
            ).await?;

            manager.create_table(
                Table::create()
                    .table(user_role::Entity)
                    .if_not_exists()
                    .col(ColumnDef::new(user_role::Column::UserId).uuid().not_null())
                    .col(ColumnDef::new(user_role::Column::RoleId).uuid().not_null())
                    .primary_key(Index::create().col(user_role::Column::UserId).col(user_role::Column::RoleId))
                    .foreign_key(
                        ForeignKey::create()
                            .name("fk-user_role-user_id")
                            .from(user_role::Entity, user_role::Column::UserId)
                            .to(user::Entity, user::Column::Id)
                            .on_delete(ForeignKeyAction::Cascade),
                    )
                    .foreign_key(
                        ForeignKey::create()
                            .name("fk-user_role-role_id")
                            .from(user_role::Entity, user_role::Column::RoleId)
                            .to(role::Entity, role::Column::Id)
                            .on_delete(ForeignKeyAction::Cascade),
                    )
                    .to_owned(),
            ).await?;

            // Seed initial roles
            let db = manager.get_connection();
            let admin_id = Uuid::new_v4();
            let user_id = Uuid::new_v4();
            db.execute(Statement::from_sql_and_values(
                manager.get_database_backend(),
                r#"INSERT INTO "roles" ("id", "name") VALUES ($1, 'ADMIN'), ($2, 'USER')"#,
                [admin_id.into(), user_id.into()],
            )).await?;

            Ok(())
        }
    }
}

// --- 7. Main Application Setup (main.rs) ---
async fn setup_database() -> Result<DatabaseConnection, DbErr> {
    // Use in-memory SQLite for a self-contained example
    let db = Database::connect("sqlite::memory:").await?;
    migrator::Migrator::up(&db, None).await?;
    println!("Database migrations completed.");
    Ok(db)
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db_conn = block_on(setup_database()).expect("Database setup failed");
    let db_conn_arc = Arc::new(db_conn);
    let user_service = web::Data::new(services::UserService::new(db_conn_arc.clone()));

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(db_conn_arc.clone()))
            .app_data(user_service.clone())
            .service(
                web::scope("/users")
                    .route("", web::post().to(handlers::create_user))
                    .route("", web::get().to(handlers::get_users))
                    .route("/{user_id}/posts", web::get().to(handlers::get_user_posts))
                    .route("/{user_id}/roles", web::post().to(handlers::assign_role_to_user))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}