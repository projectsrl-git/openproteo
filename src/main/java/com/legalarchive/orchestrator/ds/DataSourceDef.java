package com.legalarchive.orchestrator.ds;

/**
 * Reusable connection definition, configured once and referenced by id from
 * SQL and IFS steps (similar to a DBeaver connection).
 *
 * type "as400": JDBC DB2 for i via JTOpen; the same host/user/password is also
 *               used for native IFS file copy.
 * type "custom": any JDBC database via explicit jdbcUrl + driverClass.
 */
public class DataSourceDef {

    public String id;            // unique key, e.g. AS400-PROD
    public String name;          // friendly name
    public String type = "as400"; // as400 | custom

    // AS400
    public String host;          // system name / IP
    public String user;
    public String password;
    /** Extra JDBC properties for jt400, e.g. "naming=system;libraries=MYLIB" */
    public String properties;

    // custom JDBC
    public String jdbcUrl;
    public String driverClass;

    public String testQuery;     // optional override of the connection test query
}
