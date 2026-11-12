import { useEffect, useRef } from "react";

function ProfileDropdown({
  username,
  roleLabel = "Owner",
  onViewProfile,
  onChangePassword,
  onLogout,
  onClose,
}) {
  const dropdownRef = useRef(null);

  useEffect(() => {
    const handleOutsideClick = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        onClose();
      }
    };

    const handleKeyDown = (event) => {
      if (event.key === "Escape") onClose();
    };

    document.addEventListener("mousedown", handleOutsideClick);
    document.addEventListener("keydown", handleKeyDown);

    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [onClose]);

  const initial = (username || "A").trim().charAt(0).toUpperCase() || "A";

  return (
    <div className="profile-dropdown" ref={dropdownRef} role="menu" aria-label="Profile menu">
      <div className="profile-dropdown-header">
        <div className="profile-dropdown-avatar">{initial}</div>
        <div className="profile-dropdown-user">
          <strong>{username || "Admin"}</strong>
          <span>{roleLabel}</span>
        </div>
      </div>

      <div className="profile-dropdown-divider" />

      <button type="button" role="menuitem" className="profile-dropdown-item" onClick={onViewProfile}>
        <span className="profile-dropdown-icon" aria-hidden="true">👤</span>
        <span>My Profile</span>
      </button>

      <button type="button" role="menuitem" className="profile-dropdown-item" onClick={onChangePassword}>
        <span className="profile-dropdown-icon" aria-hidden="true">🔒</span>
        <span>Change Password</span>
      </button>

      <div className="profile-dropdown-divider" />

      <button type="button" role="menuitem" className="profile-dropdown-item profile-dropdown-logout" onClick={onLogout}>
        <span className="profile-dropdown-icon" aria-hidden="true">↪</span>
        <span>Logout</span>
      </button>
    </div>
  );
}

export default ProfileDropdown;
