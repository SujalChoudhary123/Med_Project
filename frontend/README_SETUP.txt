MEDTRACK - PROFILE & PASSWORD UX UPDATE

What changed:
- My Profile displays the current username and email from the Java API.
- Username and email each have their own Edit action and inline editor.
- Saving a changed username/email ends the current frontend session and redirects to Login.
- Change Password includes current/new/confirm fields, show/hide controls, live validation hints, and inline status messages.
- A successful password change ends the current frontend session and redirects to Login.
- Existing API routes are retained: GET/PUT /api/profile and POST /api/change-password.

Install the files into the matching paths shown in this ZIP, replacing the previous versions.

Important:
- Back up the existing project first.
- Restart the Java backend after replacing ApiServer.java or DatabaseHelper.java.
- Run the frontend and backend, then test profile edits and password change.
- After changing the username/email, use the new username or email on the login page. After changing the password, use the new password.
- The source was updated but full end-to-end testing against the user's local database was not possible here.
