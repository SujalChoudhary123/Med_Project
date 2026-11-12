import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class Medicine {

    // =========================================================
    // FIELDS
    // =========================================================

    private String id;
    private String name;
    private String batchNumber;
    private String category;

    private int quantity;
    private double price;

    private String manufacturer;
    private String disease;

    private LocalDate mfgDate;
    private LocalDate expiryDate;

    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public Medicine(
            String id,
            String name,
            String batchNumber,
            String category,
            int quantity,
            double price,
            String manufacturer,
            String disease,
            LocalDate mfgDate,
            LocalDate expiryDate) {

        this.id = id;
        this.name = name;
        this.batchNumber = batchNumber;
        this.category = category;
        this.quantity = quantity;
        this.price = price;
        this.manufacturer = manufacturer;
        this.disease = disease;
        this.mfgDate = mfgDate;
        this.expiryDate = expiryDate;
    }

    // =========================================================
    // GETTERS
    // =========================================================

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public String getCategory() {
        return category;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getDisease() {
        return disease;
    }

    public LocalDate getMfgDate() {
        return mfgDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    // =========================================================
    // SETTERS
    // =========================================================

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    // =========================================================
    // EXPIRY STATUS
    // =========================================================

    public String getExpiryStatus() {

        /*
         * If expiry date is missing, we cannot determine
         * the expiry status.
         */
        if (expiryDate == null) {
            return "UNKNOWN";
        }

        LocalDate today = LocalDate.now();

        // Medicine has already expired
        if (expiryDate.isBefore(today)) {
            return "EXPIRED";
        }

        long daysLeft =
                ChronoUnit.DAYS.between(
                        today,
                        expiryDate);

        // Medicine expires within 30 days
        if (daysLeft <= 30) {
            return "EXPIRING SOON";
        }

        // Medicine is safe
        return "SAFE";
    }

    // =========================================================
    // DAYS UNTIL EXPIRY
    // =========================================================

    public long getDaysUntilExpiry() {

        if (expiryDate == null) {
            return -1;
        }

        return ChronoUnit.DAYS.between(
                LocalDate.now(),
                expiryDate);
    }

    // =========================================================
    // STOCK STATUS
    // =========================================================

    public String getStockStatus() {

        if (quantity <= 0) {
            return "OUT OF STOCK";
        }

        if (quantity <= 10) {
            return "LOW STOCK";
        }

        return "NORMAL";
    }

    // =========================================================
    // TOTAL STOCK VALUE
    // =========================================================

    public double getTotalValue() {
        return quantity * price;
    }

    // =========================================================
    // STRING REPRESENTATION
    // =========================================================

    @Override
    public String toString() {

        return "Medicine{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", batchNumber='" + batchNumber + '\'' +
                ", category='" + category + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                ", manufacturer='" + manufacturer + '\'' +
                ", disease='" + disease + '\'' +
                ", mfgDate=" + mfgDate +
                ", expiryDate=" + expiryDate +
                '}';
    }
}