CREATE USER opengamma WITH PASSWORD 'OpenGamma'; 
ALTER SCHEMA public OWNER TO opengamma;
CREATE DATABASE example OWNER opengamma;
\connect example
ALTER SCHEMA public OWNER TO opengamma;
CREATE DATABASE opengamma OWNER opengamma;
\connect opengamma
ALTER SCHEMA public OWNER TO opengamma;