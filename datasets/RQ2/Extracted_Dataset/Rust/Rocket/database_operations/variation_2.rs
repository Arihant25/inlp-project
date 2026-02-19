/*
 * VARIATION 2: The "Fat Model" / ActiveRecord-esque Pattern
 *
 * This implementation co-locates data structures (models) with their corresponding
 * database operations. The logic that would be in a "repository" is now implemented
 * as associated functions or methods on the model structs themselves (e.g., `User::create(...)`).
 *
 * PROS: Less boilerplate for simple apps, intuitive for developers from OOP backgrounds.
 * CONS: Can lead to "fat models" that violate the Single Responsibility Principle,
 *       making them harder to test and maintain as complexity grows.
 */

// For setup instructions (Cargo.toml, .env, migrations), see Variation 1.
// The database schema and migration SQL are identical.

#[macro_use]
extern crate rocket;
#[macro_use]
extern crate diesel;
#[macro_use]
extern crate diesel_migrations;

use rocket::fairing::{self, AdHoc};
use rocket::http::Status;
use rocket::response::status;
use rocket::serde::json::Json;
use rocket::{Build, Rocket};
use rocket_db_pools::{diesel::PgConnection, Connection, Database};

// Main entry point
#[launch]
fn rocket() -> _ {
    dotenvy::dotenv().ok();
    rocket::build()
        .attach(DbConn::init())
        .attach(AdHoc::try_on_ignite("Diesel Migrations", run_migrations))
        .mount(
            "/",
            routes![
                routes::create_user,
                routes::get_user_with_details,
                routes::assign_role,
                routes::create_user_and_post_transaction,
                routes::get_filtered_posts,
            ],
        )
}

// Rocket's database pool fairing
#[derive(Database)]
#[database("postgres_db")]
pub struct DbConn(PgConnection);

// Diesel migrations runner (identical to Variation 1)
embed_migrations!("./migrations");
async fn run_migrations(rocket: Rocket<Build>) -> fairing::Result {
    // Assumes migrations are in a `./migrations` directory.
    // See Variation 1 for the required SQL.
    match DbConn::get_one(&rocket).await {
        Ok(conn) => {
            conn.run(|c| match embedded_migrations::run(c) {
                Ok(_) => Ok(rocket),
                Err(e) => {
                    error!("Failed to run Diesel migrations: {:?}", e);
                    Err(rocket)
                }
            })
            .await
        }
        Err(e) => {
            error!("Failed to get DB connection for migrations: {:?}", e);
            Err(rocket)
        }
    }
}

// ============== SCHEMA (identical to Variation 1) ==============
mod schema {
    diesel::table! {
        use diesel::sql_types::*;
        use super::models::PostStatusMapping;

        posts (id) {
            id -> Uuid,
            user_id -> Uuid,
            title -> Varchar,
            content -> Text,
            status -> PostStatusMapping,
        }
    }
    diesel::table! { roles (id) { id -> Uuid, name -> Varchar, } }
    diesel::table! { user_roles (user_id, role_id) { user_id -> Uuid, role_id -> Uuid, } }
    diesel::table! {
        users (id) {
            id -> Uuid,
            email -> Varchar,
            password_hash -> Varchar,
            is_active -> Bool,
            created_at -> Timestamptz,
        }
    }
    diesel::joinable!(posts -> users (user_id));
    diesel::joinable!(user_roles -> roles (role_id));
    diesel::joinable!(user_roles -> users (user_id));
    diesel::allow_tables_to_appear_in_same_query!(posts, roles, user_roles, users);
}

// ============== MODELS (with integrated DB logic) ==============
mod models {
    use super::schema::{posts, roles, user_roles, users};
    use super::DbConn;
    use chrono::{DateTime, Utc};
    use diesel::prelude::*;
    use rocket_db_pools::Connection;
    use serde::{Deserialize, Serialize};
    use thiserror::Error;
    use uuid::Uuid;
    use validator::Validate;

    // --- Custom Error Type ---
    #[derive(Error, Debug)]
    pub enum ModelError {
        #[error("Database query failed: {0}")]
        QueryError(#[from] diesel::result::Error),
        #[error("Entity not found")]
        NotFound,
    }
    type ModelResult<T> = Result<T, ModelError>;

    // --- Enums ---
    #[derive(Debug, Clone, Copy, PartialEq, Eq, diesel_derive_enum::DbEnum, Serialize, Deserialize)]
    #[ExistingTypePath = "crate::schema::sql_types::PostStatusMapping"]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    // --- User Model ---
    #[derive(Queryable, Selectable, Identifiable, Serialize, Debug)]
    #[diesel(table_name = users)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip)]
        pub password_hash: String,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Insertable)]
    #[diesel(table_name = users)]
    pub struct NewUser<'a> {
        pub id: Uuid,
        pub email: &'a str,
        pub password_hash: &'a str,
    }

    impl User {
        pub async fn create(db: &mut Connection<DbConn>, new_user: NewUser<'_>) -> ModelResult<User> {
            db.run(move |conn| {
                diesel::insert_into(users::table)
                    .values(&new_user)
                    .get_result(conn)
            })
            .await
            .map_err(ModelError::from)
        }

        pub async fn find(db: &mut Connection<DbConn>, id: Uuid) -> ModelResult<User> {
            db.run(move |conn| users::table.find(id).first(conn))
                .await
                .map_err(|e| match e {
                    diesel::result::Error::NotFound => ModelError::NotFound,
                    _ => ModelError::from(e),
                })
        }

        pub async fn get_posts(&self, db: &mut Connection<DbConn>) -> ModelResult<Vec<Post>> {
            let user_for_association = User { id: self.id, ..self.clone() };
            db.run(move |conn| Post::belonging_to(&user_for_association).load(conn))
                .await
                .map_err(ModelError::from)
        }

        pub async fn get_roles(&self, db: &mut Connection<DbConn>) -> ModelResult<Vec<Role>> {
            db.run(move |conn| {
                user_roles::table
                    .filter(user_roles::user_id.eq(self.id))
                    .inner_join(roles::table)
                    .select(Role::as_select())
                    .load(conn)
            })
            .await
            .map_err(ModelError::from)
        }
        
        pub async fn assign_role(&self, db: &mut Connection<DbConn>, role: &Role) -> ModelResult<()> {
            let new_user_role = NewUserRole { user_id: self.id, role_id: role.id };
            db.run(move |conn| {
                diesel::insert_into(user_roles::table)
                    .values(&new_user_role)
                    .on_conflict_do_nothing()
                    .execute(conn)
            })
            .await?;
            Ok(())
        }
    }

    // --- Post Model ---
    #[derive(Queryable, Selectable, Identifiable, Associations, Serialize, Debug, Clone)]
    #[diesel(belongs_to(User))]
    #[diesel(table_name = posts)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    #[derive(Insertable)]
    #[diesel(table_name = posts)]
    pub struct NewPost<'a> {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: &'a str,
        pub content: &'a str,
    }

    impl Post {
        pub async fn find_with_filters(db: &mut Connection<DbConn>, status: Option<PostStatus>) -> ModelResult<Vec<Post>> {
            db.run(move |conn| {
                let mut query = posts::table.into_boxed();
                if let Some(s) = status {
                    query = query.filter(posts::status.eq(s));
                }
                query.load(conn)
            })
            .await
            .map_err(ModelError::from)
        }
    }

    // --- Role Model ---
    #[derive(Queryable, Selectable, Identifiable, Serialize, Debug)]
    #[diesel(table_name = roles)]
    pub struct Role {
        pub id: Uuid,
        pub name: String,
    }
    
    #[derive(Insertable)]
    #[diesel(table_name = user_roles)]
    pub struct NewUserRole {
        pub user_id: Uuid,
        pub role_id: Uuid,
    }

    impl Role {
        pub async fn find_by_name(db: &mut Connection<DbConn>, name: String) -> ModelResult<Role> {
            db.run(move |conn| roles::table.filter(roles::name.eq(name)).first(conn))
                .await
                .map_err(|e| match e {
                    diesel::result::Error::NotFound => ModelError::NotFound,
                    _ => ModelError::from(e),
                })
        }
    }
    
    // --- Transactional Logic ---
    pub async fn create_user_and_post_tx(
        db: &mut Connection<DbConn>,
        new_user: NewUser<'_>,
        new_post_title: String,
        new_post_content: String,
    ) -> ModelResult<(User, Post)> {
        db.run(move |conn| {
            conn.transaction(|tx_conn| {
                let user: User = diesel::insert_into(users::table)
                    .values(&new_user)
                    .get_result(tx_conn)?;

                let new_post = NewPost {
                    id: Uuid::new_v4(),
                    user_id: user.id,
                    title: &new_post_title,
                    content: &new_post_content,
                };

                let post: Post = diesel::insert_into(posts::table)
                    .values(&new_post)
                    .get_result(tx_conn)?;

                Ok((user, post))
            })
        })
        .await
        .map_err(ModelError::from)
    }

    // --- DTOs and Response Structs ---
    #[derive(Deserialize, Validate)]
    pub struct CreateUserPayload {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8))]
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct AssignRolePayload {
        pub user_id: Uuid,
        pub role_name: String,
    }

    #[derive(Serialize)]
    pub struct UserDetailsResponse {
        pub user: User,
        pub posts: Vec<Post>,
        pub roles: Vec<Role>,
    }
}

// ============== ROUTES ==============
mod routes {
    use super::models::{self, AssignRolePayload, CreateUserPayload, ModelError, Post, PostStatus, Role, User, UserDetailsResponse};
    use super::DbConn;
    use rocket::http::Status;
    use rocket::response::status;
    use rocket::serde::json::Json;
    use rocket_db_pools::Connection;
    use uuid::Uuid;
    use validator::Validate;

    // Helper to map model errors to API responses
    fn to_api_error(e: ModelError) -> status::Custom<String> {
        let status = match e {
            ModelError::NotFound => Status::NotFound,
            _ => Status::InternalServerError,
        };
        status::Custom(status, e.to_string())
    }

    #[post("/users", format = "json", data = "<body>")]
    pub async fn create_user(
        mut db: Connection<DbConn>,
        body: Json<CreateUserPayload>,
    ) -> Result<Json<User>, status::Custom<String>> {
        body.validate().map_err(|e| status::Custom(Status::BadRequest, e.to_string()))?;

        let hashed_password = bcrypt::hash(&body.password, bcrypt::DEFAULT_COST)
            .map_err(|_| status::Custom(Status::InternalServerError, "Password hashing failed".into()))?;

        let new_user = models::NewUser {
            id: Uuid::new_v4(),
            email: &body.email,
            password_hash: &hashed_password,
        };

        User::create(&mut db, new_user).await
            .map(Json)
            .map_err(to_api_error)
    }

    #[get("/users/<id>")]
    pub async fn get_user_with_details(
        mut db: Connection<DbConn>,
        id: Uuid,
    ) -> Result<Json<UserDetailsResponse>, status::Custom<String>> {
        let user = User::find(&mut db, id).await.map_err(to_api_error)?;
        let posts = user.get_posts(&mut db).await.map_err(to_api_error)?;
        let roles = user.get_roles(&mut db).await.map_err(to_api_error)?;

        Ok(Json(UserDetailsResponse { user, posts, roles }))
    }

    #[post("/users/roles", format = "json", data = "<body>")]
    pub async fn assign_role(
        mut db: Connection<DbConn>,
        body: Json<AssignRolePayload>,
    ) -> Result<status::NoContent, status::Custom<String>> {
        let user = User::find(&mut db, body.user_id).await.map_err(to_api_error)?;
        let role = Role::find_by_name(&mut db, body.role_name.clone()).await.map_err(to_api_error)?;
        
        user.assign_role(&mut db, &role).await.map_err(to_api_error)?;

        Ok(status::NoContent)
    }

    #[post("/users/transactional", format = "json", data = "<body>")]
    pub async fn create_user_and_post_transaction(
        mut db: Connection<DbConn>,
        body: Json<CreateUserPayload>,
    ) -> Result<status::Created<String>, status::Custom<String>> {
        body.validate().map_err(|e| status::Custom(Status::BadRequest, e.to_string()))?;

        let hashed_password = bcrypt::hash(&body.password, bcrypt::DEFAULT_COST)
            .map_err(|_| status::Custom(Status::InternalServerError, "Password hashing failed".into()))?;

        let new_user = models::NewUser {
            id: Uuid::new_v4(),
            email: &body.email,
            password_hash: &hashed_password,
        };

        let (user, _) = models::create_user_and_post_tx(
            &mut db,
            new_user,
            "My First Post".to_string(),
            "This was created in a transaction!".to_string(),
        )
        .await
        .map_err(to_api_error)?;

        Ok(status::Created::new(format!("/users/{}", user.id)))
    }

    #[get("/posts?<status>")]
    pub async fn get_filtered_posts(
        mut db: Connection<DbConn>,
        status: Option<PostStatus>,
    ) -> Result<Json<Vec<Post>>, status::Custom<String>> {
        Post::find_with_filters(&mut db, status)
            .await
            .map(Json)
            .map_err(to_api_error)
    }
}