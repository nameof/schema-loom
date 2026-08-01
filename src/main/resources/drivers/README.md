# SchemaLoom JDBC drivers

Place each vendor JDBC JAR and its `.properties` descriptor in this directory.
The Maven test runtime copies this directory to the classpath, and production
code loads it with `new JdbcDriverLoader()`.

Do not commit vendor JARs or credentials. See the root README for the
descriptor format and security requirements.
