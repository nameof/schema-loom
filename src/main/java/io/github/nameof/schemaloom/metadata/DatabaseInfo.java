package io.github.nameof.schemaloom.metadata;

public final class DatabaseInfo {
    private final String productName, productVersion, driverName, driverVersion, url;
    public DatabaseInfo(String productName, String productVersion, String driverName, String driverVersion, String url) {
        this.productName = productName; this.productVersion = productVersion; this.driverName = driverName; this.driverVersion = driverVersion; this.url = url;
    }
    public String getProductName() { return productName; }
    public String getProductVersion() { return productVersion; }
    public String getDriverName() { return driverName; }
    public String getDriverVersion() { return driverVersion; }
    public String getUrl() { return url; }
}
