import java.sql.*;

public class TestGBase {
    public static void main(String[] args) throws Exception {
        Class.forName("com.gbase.jdbc.Driver");
        try (Connection conn = DriverManager.getConnection("jdbc:gbase://localhost:15258/gbase", "gbase", "gbase")) {
            System.out.println("Connected to GBase 8a!");
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT VERSION()");
                if (rs.next()) {
                    System.out.println("Version: " + rs.getString(1));
                }
            }
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
