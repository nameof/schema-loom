import java.sql.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class TestDb2DriverWithFilter {
    static class FilteredClassLoader extends URLClassLoader {
        private final List<String> childPackages;
        
        FilteredClassLoader(URL[] urls, ClassLoader parent, List<String> packages) {
            super(urls, parent);
            this.childPackages = packages;
        }
        
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String p : childPackages) {
                if (name.equals(p) || name.startsWith(p + ".")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            try {
                                c = findClass(name);
                            } catch (ClassNotFoundException e) {
                                System.err.println("Failed to find class in JARs: " + name);
                                // Fall through to parent
                            }
                        }
                        if (c != null) {
                            if (resolve) resolveClass(c);
                            return c;
                        }
                    }
                }
            }
            return super.loadClass(name, resolve);
        }
    }
    
    public static void main(String[] args) throws Exception {
        File jar1 = new File("drivers/db2jcc4.jar");
        File jar2 = new File("drivers/db2jcc_license_cisuz.jar");
        File jar3 = new File("drivers/Common.jar");
        
        URL[] urls = new URL[] {
            jar1.toURI().toURL(),
            jar2.toURI().toURL(),
            jar3.toURI().toURL()
        };
        
        List<String> childPackages = Arrays.asList("com.ibm.db2");
        ClassLoader parent = TestDb2DriverWithFilter.class.getClassLoader();
        
        ClassLoader loader = new FilteredClassLoader(urls, parent, childPackages);
        
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
        }
    }
}
