-- One-time local setup: creates the application role and the app + test databases.
-- Run as the postgres superuser, passing the app password on the command line
-- (never commit it):
--
--   "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -v app_pw="<choose-a-password>" -f db/setup-local-db.sql
--
-- Then set the same value in the environment before running the app/tests:
--   PowerShell:  $env:EKG_DB_PASSWORD = "<choose-a-password>"
--   (persist:    [Environment]::SetEnvironmentVariable("EKG_DB_PASSWORD","<pw>","User"))

CREATE ROLE ekg_app LOGIN PASSWORD :'app_pw';
CREATE DATABASE ekg      OWNER ekg_app ENCODING 'UTF8';
CREATE DATABASE ekg_test OWNER ekg_app ENCODING 'UTF8';
