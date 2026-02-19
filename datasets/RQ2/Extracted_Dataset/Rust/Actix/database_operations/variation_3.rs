//! Variation 3: The Functional/Handler-Centric Developer
//! This style minimizes abstraction layers, placing most logic directly inside Actix handlers.
//! It's fast for prototyping and suitable for smaller services or microservices where
//! extensive layering might be overkill. Variable names are often shorter (e.g., `db`, `req`).

use actix_web::{web, App, HttpServer, Responder, HttpResponse, ResponseError};
use sea_orm::{prelude::*, sea_query::OnConflict, ActiveValue, Database, DatabaseConnection, DbErr, EntityTrait, TransactionTrait};
use sea_orm_migration::prelude::*;
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use futures::executor::block_on;

// --- Global Error Type ---
#[derive(Debug)]
struct HttpError(anyhow::Error);

impl Display for HttpError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl<T: Into<anyhow::Error>> From<T> for HttpError {
    fn from(err: T) -> Self {
        HttpError(err.into())
    }
}

impl ResponseError for HttpError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        // A real implementation would inspect the error chain
        actix_web::http::StatusCode::INTERNAL_SERVER_ERROR
    }
}

// --- Models (models.rs) ---
mod entity {
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
        #[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
        pub enum Relation {}
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

// --- Handlers with Inline Logic (handlers.rs) ---
mod api {
    use super::entity::{post, role, user, user_role};
    use super::HttpError;
    use actix_web::{web, HttpResponse, Responder};
    use sea_orm::{prelude::*, ActiveValue, ColumnTrait, Condition, DatabaseConnection, EntityTrait, QueryFilter, TransactionTrait};
    use serde::Deserialize;

    #[derive(Deserialize)]
    pub struct CreateUserReq { email: String, password: String }

    #[derive(Deserialize)]
    pub struct UserQuery { is_active: Option<bool> }

    #[derive(Deserialize)]
    pub struct AssignRoleReq { role_name: String }

    // Transaction logic directly in the handler
    pub async fn create_user(db: web::Data<DatabaseConnection>, req: web::Json<CreateUserReq>) -> Result<impl Responder, HttpError> {
        let user = db.transaction::<_, _, DbErr>(|txn| {
            Box::pin(async move {
                let default_role = role::Entity::find()
                    .filter(role::Column::Name.eq("USER"))
                    .one(txn).await?
                    .ok_or_else(|| DbErr::RecordNotFound("Default role 'USER' not found".to_string()))?;

                let new_user = user::ActiveModel {
                    id: ActiveValue::Set(Uuid::new_v4()),
                    email: ActiveValue::Set(req.email.clone()),
                    password_hash: ActiveValue::Set("...hashed...".to_string()),
                    is_active: ActiveValue::Set(true),
                    created_at: ActiveValue::Set(chrono::Utc::now()),
                };
                let user = new_user.insert(txn).await?;

                user_role::ActiveModel {
                    user_id: ActiveValue::Set(user.id),
                    role_id: ActiveValue::Set(default_role.id),
                }.insert(txn).await?;

                Ok(user)
            })
        }).await?;

        Ok(HttpResponse::Created().json(user))
    }

    // Query building directly in the handler
    pub async fn get_users(db: web::Data<DatabaseConnection>, query: web::Query<UserQuery>) -> Result<impl Responder, HttpError> {
        let mut select = user::Entity::find();
        if let Some(is_active) = query.is_active {
            select = select.filter(user::Column::IsActive.eq(is_active));
        }
        let users = select.all(db.as_ref()).await?;
        Ok(HttpResponse::Ok().json(users))
    }

    // One-to-many relationship query in handler
    pub async fn get_user_posts(db: web::Data<DatabaseConnection>, path: web::Path<Uuid>) -> Result<impl Responder, HttpError> {
        let user_id = path.into_inner();
        let posts = post::Entity::find()
            .filter(post::Column::UserId.eq(user_id))
            .all(db.as_ref()).await?;
        Ok(HttpResponse::Ok().json(posts))
    }

    // Many-to-many relationship logic in handler
    pub async fn assign_role_to_user(db: web::Data<DatabaseConnection>, path: web::Path<Uuid>, req: web::Json<AssignRoleReq>) -> Result<impl Responder, HttpError> {
        let user_id = path.into_inner();
        let role = role::Entity::find()
            .filter(role::Column::Name.eq(&req.role_name))
            .one(db.as_ref()).await?
            .ok_or_else(|| DbErr::RecordNotFound(format!("Role '{}' not found", req.role_name)))?;

        user_role::ActiveModel {
            user_id: ActiveValue::Set(user_id),
            role_id: ActiveValue::Set(role.id),
        }.insert(db.as_ref()).await?;

        Ok(HttpResponse::Ok().finish())
    }
}

// --- Database Migrations (db_setup.rs) ---
// This part is functionally identical to other variations.
mod db_setup {
    use sea_orm::{prelude::Uuid, sea_query::Table, ConnectionTrait, DbErr, Statement};
    use sea_orm_migration::prelude::*;
    use super::entity::{user, post, role, user_role};

    pub struct Migrator;

    #[async_trait::async_trait]
    impl MigratorTrait for Migrator {
        fn migrations() -> Vec<Box<dyn MigrationTrait>> { vec![Box::new(Migration)] }
    }

    struct Migration;

    #[async_trait::async_trait]
    impl MigrationTrait for Migration {
        async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager.create_table(Table::create().table(user::Entity).if_not_exists()
                .col(ColumnDef::new(user::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(user::Column::Email).string().not_null().unique_key())
                .col(ColumnDef::new(user::Column::PasswordHash).string().not_null())
                .col(ColumnDef::new(user::Column::IsActive).boolean().not_null())
                .col(ColumnDef::new(user::Column::CreatedAt).timestamp_with_time_zone().not_null())
                .to_owned()).await?;

            manager.create_table(Table::create().table(post::Entity).if_not_exists()
                .col(ColumnDef::new(post::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(post::Column::UserId).uuid().not_null())
                .col(ColumnDef::new(post::Column::Title).string().not_null())
                .col(ColumnDef::new(post::Column::Content).text().not_null())
                .col(ColumnDef::new(post::Column::Status).string().not_null())
                .foreign_key(ForeignKey::create().name("fk-post-user_id").from(post::Entity, post::Column::UserId).to(user::Entity, user::Column::Id).on_delete(ForeignKeyAction::Cascade))
                .to_owned()).await?;

            manager.create_table(Table::create().table(role::Entity).if_not_exists()
                .col(ColumnDef::new(role::Column::Id).uuid().not_null().primary_key())
                .col(ColumnDef::new(role::Column::Name).string().not_null().unique_key())
                .to_owned()).await?;

            manager.create_table(Table::create().table(user_role::Entity).if_not_exists()
                .col(ColumnDef::new(user_role::Column::UserId).uuid().not_null())
                .col(ColumnDef::new(user_role::Column::RoleId).uuid().not_null())
                .primary_key(Index::create().col(user_role::Column::UserId).col(user_role::Column::RoleId))
                .foreign_key(ForeignKey::create().name("fk-user_role-user_id").from(user_role::Entity, user_role::Column::UserId).to(user::Entity, user::Column::Id).on_delete(ForeignKeyAction::Cascade))
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

// --- Main Application Setup ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db = block_on(async {
        let conn = Database::connect("sqlite::memory:").await.unwrap();
        db_setup::Migrator::up(&conn, None).await.unwrap();
        conn
    });

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(db.clone()))
            .service(
                web::scope("/users")
                    .route("", web::post().to(api::create_user))
                    .route("", web::get().to(api::get_users))
                    .route("/{user_id}/posts", web::get().to(api::get_user_posts))
                    .route("/{user_id}/roles", web::post().to(api::assign_role_to_user))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}