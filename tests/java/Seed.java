import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Prepares a brand-new test database:
 *  - own_a      owner of a new shop (password OwnerPass1)
 *  - legacy_u   owner of shop 1, password stored the OLD way (unsalted SHA-256)
 *  - platform   the platform admin (password AdminPass1)
 *  - EXP1       an expired medicine in shop 1 (to prove expired stock cannot be sold)
 */
public class Seed {
    public static void main(String[] args) throws Exception {
        DatabaseHelper.initializeDatabase();

        if (!DatabaseHelper.registerUser("own_a", "own_a@test.com", "OwnerPass1")) {
            throw new IllegalStateException("could not register own_a");
        }

        if (!DatabaseHelper.registerUser("platform", "platform@test.com", "AdminPass1")) {
            throw new IllegalStateException("could not register platform");
        }

        byte[] hash = MessageDigest.getInstance("SHA-256").digest("LegacyPass1".getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));

        try (Connection c = DatabaseHelper.connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(username,password,email,shop_id,role) "
                            + "VALUES('legacy_u',?, 'legacy_u@test.com',1,'owner')")) {
                ps.setString(1, hex.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO medicines(shop_id,id,name,batchNumber,category,quantity,price,"
                            + "manufacturer,disease,mfgDate,expiryDate) "
                            + "VALUES(1,'EXP1','Expired Test','B1','Tablet',5,20,'X','Y','2019-01-01','2020-01-01')")) {
                ps.executeUpdate();
            }
        }

        System.out.println("seeded");
    }
}
