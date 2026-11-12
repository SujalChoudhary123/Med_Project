Medicine Management System - Fixed Version

Fixes included:
- Fixed database path resolution so the seeded medicine_inventory.db is used when launching from the project root or IDE. This prevents an empty database from being created in the wrong working directory.
- Medicine Inventory now keeps ID and Name columns clearly visible with sensible column widths; wider tables remain horizontally scrollable.
- Added sales-table initialization to Main so all database tables exist before the UI starts.
- Recompiled/tested all Java source files successfully.
- Verified the login screen and inventory screen launch correctly and inventory records display their IDs and names.

Run in VS Code / any Java IDE:
1. Open the MedicineManagementSystem folder.
2. Make sure lib/*.jar is included on the Java classpath (VS Code settings already includes it).
3. Run src/Main.java.

Default admin login:
Username: admin
Password: admin123
