import java.sql.*;
import java.net.*;
import java.io.*;

public class TestDb2Driver {
    public static void main(String[] args) throws Exception {
        File jar1 = new File("drivers/db2jcc4.jar");
        File jar2 = new File("drivers/db2jcc_license_cisuz.jar");
        File jar3 = new File("drivers/Common.jar");
        
        URL[] urls = new URL[] {
            jar1.toURI().toURL(),
            jar2.toURI().toURL(),
            jar3.toURI().toURL()
        };
        
        URLClassLoader loader = new URLClassLoader(urls, TestDb2Driver.class.getClassLoader());
        
        try {
            Class<?> driverClass = Class.forName("com.ibm.db2.jcc.DB2Driver", true, loader);
            System.out.println("Driver class loaded: " + driverClass);
            
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            System.out.println("Driver instance created: " + driver);
            
            String url = "jdbc:db2://localhost:15000/BLUDB";
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", "db2inst1");
            props.setProperty("password", "Test1234");
            
            System.out.println("Attempting to connect to: " + url);
            Connection conn = driver.connect(url, props);
            System.out.println("Connection successful: " + conn);
            
            if (conn != null) {
                System.out.println("Database version: " + conn.getMetaData().getDatabaseProductVersion());
                conn.close();
            }
        } catch (Throwable t) {
            System.err.println("Error: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
            if (t.getCause() != null) {
                System.err.println("Caused by: " + t.getCause());
                t.getCause().printStackTrace();
            }
        }
    }
}
