//! Variation 4: The CQRS-Inspired Developer
//! This pattern separates read (Query) and write (Command) operations. Handlers
//! create and dispatch command/query objects, which are then processed by dedicated
//! handlers. This promotes scalability, maintainability, and clear intent, though it
//! adds boilerplate for simple applications.

use actix_web::{web, App, HttpServer, Responder, HttpResponse, ResponseError};
use sea_orm::{prelude::*, sea_query::OnConflict, ActiveValue, Database, DatabaseConnection, DbErr, EntityTrait, TransactionTrait};
use sea_orm_migration::prelude::*;
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use std::sync::Arc;
use futures::executor::block_on;

// --- 1. Shared Infrastructure (Error, State) ---
#[derive(Debug, thiserror::Error)]
pub enum DomainError {
    #[error("Database error: {0}")]
    Db(#[from] DbErr),
    #[error("Not Found: {0}")]
    NotFound(String),
    #[error("Validation Error: {0}")]
    Validation(String),
}

impl ResponseError for DomainError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            DomainError::Db(_) => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
            DomainError::NotFound(_) => actix_web::http::StatusCode::NOT_FOUND,
            DomainError::Validation(_) => actix_web::http::StatusCode::BAD_REQUEST,
        }
    }
}

pub struct AppState {
    db: DatabaseConnection,
}

// --- 2. Models (entities/mod.rs) ---
// These are plain data structures, logic is in commands/queries.
mod entities {
    pub mod user {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};
        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "users")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)] pub id: Uuid,
            #[sea_orm(unique)] pub email: String,
            pub password_hash: String,
            pub is_active: bool,
            pub created_at: ChronoDateTimeUtc,
        }
        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(has_many = "super::post::Entity")] Post,
            #[sea_orm(has_many = "super::user_role::Entity")] UserRole,
        }
        impl Related<super::post::Entity> for Entity { fn to() -> RelationDef { Relation::Post.def() } }
        impl Related<super::role::Entity> for Entity {
            fn to() -> RelationDef { Relation::UserRole.def() }
            fn via() -> Option<RelationDef> { Some(super::user_role::Relation::Role.def().rev()) }
        }
        impl ActiveModelBehavior for ActiveModel {}
    }
    pub mod post {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};
        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "posts")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)] pub id: Uuid,
            pub user_id: Uuid,
            pub title: String,
            #[sea_orm(column_type = "Text")] pub content: String,
            pub status: PostStatus,
        }
        #[derive(Debug, Clone, PartialEq, Eq, EnumIter, DeriveActiveEnum, Serialize, Deserialize)]
        #[sea_orm(rs_type = "String", db_type = "String(Some(10))")]
        pub enum PostStatus { #[sea_orm(string_value = "DRAFT")] Draft, #[sea_orm(string_value = "PUBLISHED")] Published }
        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(belongs_to = "super::user::Entity", from = "Column::UserId", to = "super::user::Column::Id")] User,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }
    pub mod role {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};
        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "roles")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)] pub id: Uuid,
            #[sea_orm(unique)] pub name: String,
        }
        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)] pub enum Relation {}
        impl ActiveModelBehavior for ActiveModel {}
    }
    pub mod user_role {
        use sea_orm::entity::prelude::*;
        use serde::{Deserialize, Serialize};
        #[derive(Clone, Debug, PartialEq, Eq, DeriveEntityModel, Serialize, Deserialize)]
        #[sea_orm(table_name = "user_roles")]
        pub struct Model {
            #[sea_orm(primary_key, auto_increment = false)] pub user_id: Uuid,
            #[sea_orm(primary_key, auto_increment = false)] pub role_id: Uuid,
        }
        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {
            #[sea_orm(belongs_to = "super::user::Entity", from = "Column::UserId", to = "super::user::Column::Id")] User,
            #[sea_orm(belongs_to = "super::role::Entity", from = "Column::RoleId", to = "super::role::Column::Id")] Role,
        }
        impl ActiveModelBehavior for ActiveModel {}
    }
}

// --- 3. Commands (Write Operations) ---
mod commands {
    use super::entities::{user, role, user_role};
    use super::DomainError;
    use sea_orm::{prelude::*, ActiveValue, DatabaseConnection, EntityTrait, TransactionTrait};
    use serde::Deserialize;

    // Command Definitions
    #[derive(Deserialize)]
    pub struct CreateUser { pub email: String, pub password: String }
    #[derive(Deserialize)]
    pub struct AssignRole { pub user_id: Uuid, pub role_name: String }

    // Command Handler
    pub struct CommandHandler<'a> { db: &'a DatabaseConnection }

    impl<'a> CommandHandler<'a> {
        pub fn new(db: &'a DatabaseConnection) -> Self { Self { db } }

        // Transactional command execution
        pub async fn handle_create_user(&self, cmd: CreateUser) -> Result<user::Model, DomainError> {
            self.db.transaction::<_, _, DomainError>(|txn| {
                Box::pin(async move {
                    if user::Entity::find().filter(user::Column::Email.eq(&cmd.email)).one(txn).await?.is_some() {
                        return Err(DomainError::Validation("Email already exists".to_string()));
                    }
                    let default_role = role::Entity::find().filter(role::Column::Name.eq("USER")).one(txn).await?
                        .ok_or_else(|| DomainError::NotFound("Default role 'USER' not found".to_string()))?;

                    let new_user = user::ActiveModel {
                        id: ActiveValue::Set(Uuid::new_v4()),
                        email: ActiveValue::Set(cmd.email),
                        password_hash: ActiveValue::Set("...hashed...".to_string()),
                        is_active: ActiveValue::Set(true),
                        created_at: ActiveValue::Set(chrono::Utc::now()),
                    }.insert(txn).await?;

                    user_role::ActiveModel {
                        user_id: ActiveValue::Set(new_user.id),
                        role_id: ActiveValue::Set(default_role.id),
                    }.insert(txn).await?;

                    Ok(new_user)
                })
            }).await.map_err(|e| match e {
                sea_orm::TransactionError::Connection(dbe) => DomainError::Db(dbe),
                sea_orm::TransactionError::Transaction(de) => de,
            })
        }

        pub async fn handle_assign_role(&self, cmd: AssignRole) -> Result<(), DomainError> {
            let role_to_assign = role::Entity::find().filter(role::Column::Name.eq(&cmd.role_name)).one(self.db).await?
                .ok_or_else(|| DomainError::NotFound(format!("Role '{}' not found", cmd.role_name)))?;
            
            user_role::ActiveModel {
                user_id: ActiveValue::Set(cmd.user_id),
                role_id: ActiveValue::Set(role_to_assign.id),
            }.insert(self.db).await?;

            Ok(())
        }
    }
}

// --- 4. Queries (Read Operations) ---
mod queries {
    use super::entities::{user, post};
    use super::DomainError;
    use sea_orm::{prelude::*, ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter};
    use serde::Deserialize;

    // Query Definitions
    #[derive(Deserialize)]
    pub struct GetUsers { pub is_active: Option<bool> }
    pub struct GetUserPosts { pub user_id: Uuid }

    // Query Handler
    pub struct QueryHandler<'a> { db: &'a DatabaseConnection }

    impl<'a> QueryHandler<'a> {
        pub fn new(db: &'a DatabaseConnection) -> Self { Self { db } }

        pub async fn handle_get_users(&self, query: GetUsers) -> Result<Vec<user::Model>, DomainError> {
            let mut select = user::Entity::find();
            if let Some(is_active) = query.is_active {
                select = select.filter(user::Column::IsActive.eq(is_active));
            }
            Ok(select.all(self.db).await?)
        }

        pub async fn handle_get_user_posts(&self, query: GetUserPosts) -> Result<Vec<post::Model>, DomainError> {
            let user = user::Entity::find_by_id(query.user_id).one(self.db).await?
                .ok_or_else(|| DomainError::NotFound(format!("User {} not found", query.user_id)))?;
            Ok(user.find_related(post::Entity).all(self.db).await?)
        }
    }
}

// --- 5. API Handlers (Dispatchers) ---
mod api_handlers {
    use super::commands::{self, AssignRole, CreateUser};
    use super::queries::{self, GetUserPosts, GetUsers};
    use super::{AppState, DomainError};
    use actix_web::{web, HttpResponse, Responder};
    use uuid::Uuid;

    pub async fn create_user(state: web::Data<AppState>, cmd: web::Json<CreateUser>) -> Result<impl Responder, DomainError> {
        let handler = commands::CommandHandler::new(&state.db);
        let user = handler.handle_create_user(cmd.into_inner()).await?;
        Ok(HttpResponse::Created().json(user))
    }

    pub async fn get_users(state: web::Data<AppState>, query: web::Query<GetUsers>) -> Result<impl Responder, DomainError> {
        let handler = queries::QueryHandler::new(&state.db);
        let users = handler.handle_get_users(query.into_inner()).await?;
        Ok(HttpResponse::Ok().json(users))
    }

    pub async fn get_user_posts(state: web::Data<AppState>, path: web::Path<Uuid>) -> Result<impl Responder, DomainError> {
        let handler = queries::QueryHandler::new(&state.db);
        let query = GetUserPosts { user_id: path.into_inner() };
        let posts = handler.handle_get_user_posts(query).await?;
        Ok(HttpResponse::Ok().json(posts))
    }

    pub async fn assign_role(state: web::Data<AppState>, path: web::Path<Uuid>, body: web::Json<serde_json::Value>) -> Result<impl Responder, DomainError> {
        let handler = commands::CommandHandler::new(&state.db);
        let role_name: String = serde_json::from_value(body.get("role_name").cloned().unwrap_or_default())
            .map_err(|_| DomainError::Validation("Missing or invalid role_name".into()))?;
        let cmd = AssignRole { user_id: path.into_inner(), role_name };
        handler.handle_assign_role(cmd).await?;
        Ok(HttpResponse::Ok().finish())
    }
}

// --- 6. Migrations ---
// Functionally identical to other variations.
mod migrator {
    use sea_orm::{prelude::Uuid, sea_query::Table, ConnectionTrait, DbErr, Statement};
    use sea_orm_migration::prelude::*;
    use super::entities::{user, post, role, user_role};
    pub struct Migrator;
    #[async_trait::async_trait]
    impl MigratorTrait for Migrator { fn migrations() -> Vec<Box<dyn MigrationTrait>> { vec![Box::new(Migration)] } }
    struct Migration;
    #[async_trait::async_trait]
    impl MigrationTrait for Migration {
        async fn up(&self, m: &SchemaManager) -> Result<(), DbErr> {
            m.create_table(Table::create().table(user::Entity).if_not_exists().col(ColumnDef::new(user::Column::Id).uuid().not_null().primary_key()).col(ColumnDef::new(user::Column::Email).string().not_null().unique_key()).col(ColumnDef::new(user::Column::PasswordHash).string().not_null()).col(ColumnDef::new(user::Column::IsActive).boolean().not_null()).col(ColumnDef::new(user::Column::CreatedAt).timestamp_with_time_zone().not_null()).to_owned()).await?;
            m.create_table(Table::create().table(post::Entity).if_not_exists().col(ColumnDef::new(post::Column::Id).uuid().not_null().primary_key()).col(ColumnDef::new(post::Column::UserId).uuid().not_null()).col(ColumnDef::new(post::Column::Title).string().not_null()).col(ColumnDef::new(post::Column::Content).text().not_null()).col(ColumnDef::new(post::Column::Status).string().not_null()).foreign_key(ForeignKey::create().name("fk-post-user_id").from(post::Entity, post::Column::UserId).to(user::Entity, user::Column::Id).on_delete(ForeignKeyAction::Cascade)).to_owned()).await?;
            m.create_table(Table::create().table(role::Entity).if_not_exists().col(ColumnDef::new(role::Column::Id).uuid().not_null().primary_key()).col(ColumnDef::new(role::Column::Name).string().not_null().unique_key()).to_owned()).await?;
            m.create_table(Table::create().table(user_role::Entity).if_not_exists().col(ColumnDef::new(user_role::Column::UserId).uuid().not_null()).col(ColumnDef::new(user_role::Column::RoleId).uuid().not_null()).primary_key(Index::create().col(user_role::Column::UserId).col(user_role::Column::RoleId)).foreign_key(ForeignKey::create().name("fk-user_role-user_id").from(user_role::Entity, user_role::Column::UserId).to(user::Entity, user::Column::Id).on_delete(ForeignKeyAction::Cascade)).foreign_key(ForeignKey::create().name("fk-user_role-role_id").from(user_role::Entity, user_role::Column::RoleId).to(role::Entity, role::Column::Id).on_delete(ForeignKeyAction::Cascade)).to_owned()).await?;
            let db = m.get_connection();
            db.execute(Statement::from_sql_and_values(m.get_database_backend(), r#"INSERT INTO "roles" ("id", "name") VALUES ($1, 'ADMIN'), ($2, 'USER') ON CONFLICT DO NOTHING"#, [Uuid::new_v4().into(), Uuid::new_v4().into()])).await?;
            Ok(())
        }
    }
}

// --- 7. Main Application Setup ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db = block_on(async {
        let conn = Database::connect("sqlite::memory:").await.unwrap();
        migrator::Migrator::up(&conn, None).await.unwrap();
        conn
    });

    let app_state = web::Data::new(AppState { db });

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_state.clone())
            .service(
                web::scope("/users")
                    .route("", web::post().to(api_handlers::create_user))
                    .route("", web::get().to(api_handlers::get_users))
                    .route("/{user_id}/posts", web::get().to(api_handlers::get_user_posts))
                    .route("/{user_id}/roles", web::post().to(api_handlers::assign_role))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}