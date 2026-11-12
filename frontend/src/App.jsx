import { useEffect, useState } from "react";
import { api } from "./config.js";
import "./App.css";
import "./theme.css";
import Icon from "./components/layout/Icons.jsx";
import { DEFAULT_SETTINGS, syncPharmacyName } from "./utils/appUtils.js";
import AuthToast from "./components/auth/AuthToast.jsx";
import LogoutConfirmModal from "./components/auth/LogoutConfirmModal.jsx";
import LoginScreen from "./components/auth/LoginScreen.jsx";
import SignupScreen from "./components/auth/SignupScreen.jsx";
import Dashboard from "./components/dashboard/Dashboard.jsx";
import Inventory from "./components/inventory/Inventory.jsx";
import AddMedicine from "./components/inventory/AddMedicine.jsx";
import SalesBilling from "./components/sales/SalesBilling.jsx";
import Customers from "./components/customers/Customers.jsx";
import Purchases from "./components/purchases/Purchases.jsx";
import Activity from "./components/activity/Activity.jsx";
import PlanPage from "./components/plan/PlanPage.jsx";
import Admin from "./components/admin/Admin.jsx";
import Help from "./components/help/Help.jsx";
import Landing from "./components/landing/Landing.jsx";
import StatusBanners from "./components/layout/StatusBanners.jsx";
import Alerts from "./components/alerts/Alerts.jsx";
import Reports from "./components/reports/Reports.jsx";
import Settings from "./components/settings/Settings.jsx";
import ProfileDropdown from "./components/profile/ProfileDropdown.jsx";
import MyProfilePage from "./components/profile/MyProfilePage.jsx";
import ChangePasswordPage from "./components/profile/ChangePasswordPage.jsx";
import Team from "./components/team/Team.jsx";

const ROLE_LABELS = {
  owner: "Owner",
  pharmacist: "Pharmacist",
  cashier: "Cashier",
};

const PAGE_NOTES = {
  Dashboard: "A quick look at your sales and stock today.",
  Purchases: "Stock coming in, suppliers and what you owe.",
  Activity: "Who did what, and a download of all your data.",
  Plan: "Your plan and how to renew it.",
  Help: "Short guides in English and Hindi.",
  Admin: "Every shop on MedTrack.",
};

function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(() =>
    localStorage.getItem("medtrackLoggedIn") === "true"
  );
  const [loggedInUsername, setLoggedInUsername] = useState(() =>
    localStorage.getItem("medtrackUsername") || ""
  );
  // The server enforces permissions; this only decides what the UI shows.
  // Unknown role falls back to the most restricted one.
  const [role, setRole] = useState(() =>
    localStorage.getItem("medtrackRole") || "cashier"
  );
  // While MedTrack is free with no end date there is nothing to show on a Plan page.
  const [hidePlan, setHidePlan] = useState(true);
  const [isAdmin, setIsAdmin] = useState(() =>
    localStorage.getItem("medtrackAdmin") === "true"
  );
  const [showSignup, setShowSignup] = useState(false);
  // Visitors see the landing page. People who have used the app, or who opened
  // the installed app, go straight to the login form.
  const [showLanding, setShowLanding] = useState(() =>
    !localStorage.getItem("medtrackReturning") &&
    !window.location.search.includes("app=1")
  );
  const [showLogoutConfirm, setShowLogoutConfirm] = useState(false);
  const [authNotice, setAuthNotice] = useState("");
  const [activePage, setActivePage] = useState("Dashboard");
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  // Phones and tablets: the sidebar becomes a slide-in menu.
  const [menuOpen, setMenuOpen] = useState(false);

  const [settings, setSettings] = useState(() => {
    try {
      return {
        ...DEFAULT_SETTINGS,
        ...JSON.parse(
          localStorage.getItem("medtrackSettings") || "{}"
        ),
      };
    } catch {
      return {
        ...DEFAULT_SETTINGS,
      };
    }
  });

  const [settingsVersion, setSettingsVersion] = useState(0);

  const ALL = ["owner", "pharmacist", "cashier"];
  const STOCK = ["owner", "pharmacist"];

  const menuItems = [
    { name: "Dashboard", icon: "grid", roles: ALL },
    { name: "Inventory", icon: "pill", roles: ALL },
    { name: "Add Medicine", icon: "plus", roles: STOCK },
    { name: "Sales & Billing", icon: "receipt", roles: ALL },
    { name: "Purchases", icon: "plus", roles: STOCK },
    { name: "Customers", icon: "users", roles: ALL },
    { name: "Alerts", icon: "bell", roles: ALL },
    { name: "Reports", icon: "chart", roles: STOCK },
    { name: "Team", icon: "users", roles: ["owner"] },
    { name: "Activity", icon: "shield", roles: ["owner"] },
    ...(hidePlan ? [] : [{ name: "Plan", icon: "wallet", roles: ["owner"] }]),
    { name: "Help", icon: "info", roles: ALL },
    ...(isAdmin ? [{ name: "Admin", icon: "grid", roles: ALL }] : []),
  ].filter((item) => item.roles.includes(role));

  const canManageStock = STOCK.includes(role);
  const canOpenSettings = STOCK.includes(role);

  const allowedPages = new Set([
    ...menuItems.map((item) => item.name),
    "My Profile",
    "Change Password",
    ...(canOpenSettings ? ["Settings"] : []),
  ]);

  /*
   * Keep application settings synchronized.
   * Whenever Settings saves something, every page is refreshed.
   */
  useEffect(() => {
    const handleSettingsChange = () => {
      try {
        const updated = JSON.parse(
          localStorage.getItem("medtrackSettings") || "{}"
        );

        setSettings({
          ...DEFAULT_SETTINGS,
          ...updated,
        });

        setSettingsVersion((value) => value + 1);
      } catch (error) {
        console.error("Unable to load settings:", error);
      }
    };

    window.addEventListener(
      "medtrack-settings-changed",
      handleSettingsChange
    );

    return () => {
      window.removeEventListener(
        "medtrack-settings-changed",
        handleSettingsChange
      );
    };
  }, []);

  // The shop name lives on the server (it is printed on invoices); keep the
  // sidebar title in step with it after signing in.
  useEffect(() => {
    if (!isLoggedIn) return;

    fetch(api("/api/shop"))
      .then((response) => (response.ok ? response.json() : null))
      .then((shop) => {
        if (shop && shop.name) syncPharmacyName(shop.name);
      })
      .catch(() => {});

    fetch(api("/api/plan"))
      .then((response) => (response.ok ? response.json() : null))
      .then((plan) => {
        if (plan) setHidePlan(plan.state === "free" && !plan.endsOn);
      })
      .catch(() => {});
  }, [isLoggedIn]);

  useEffect(() => {
    if (!settings.autoRefresh) return undefined;
    const timer = setInterval(() => {
      window.dispatchEvent(new Event("medtrack-auto-refresh"));
    }, 30000);
    return () => clearInterval(timer);
  }, [settings.autoRefresh]);

  // Escape closes the phone menu.
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onKey = (event) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [menuOpen]);

  const handleNavigation = (page) => {
    setMenuOpen(false);
    setProfileMenuOpen(false);
    if (!allowedPages.has(page)) return;
    setActivePage(page);
  };

  const handleLoginSuccess = (username, message = "Welcome back! You have signed in successfully.") => {
    localStorage.setItem("medtrackLoggedIn", "true");
    localStorage.setItem("medtrackUsername", username);
    localStorage.setItem("medtrackReturning", "1");
    setRole(localStorage.getItem("medtrackRole") || "cashier");
    setIsAdmin(localStorage.getItem("medtrackAdmin") === "true");
    setLoggedInUsername(username);
    setActivePage("Dashboard");
    setShowSignup(false);
    setAuthNotice(message);
    setIsLoggedIn(true);
  };

  const handleLogout = () => {
    fetch(api("/api/auth/logout"), { method: "POST" }).catch(() => {});
    localStorage.removeItem("medtrackToken");
    localStorage.removeItem("medtrackRole");
    localStorage.removeItem("medtrackLoggedIn");
    localStorage.removeItem("medtrackUsername");
    localStorage.removeItem("medtrackAdmin");
    setIsAdmin(false);
    setLoggedInUsername("");
    setActivePage("Dashboard");
    setShowLogoutConfirm(false);
    setAuthNotice("You have been logged out successfully.");
    setIsLoggedIn(false);
  };

  // Credential changes end the current session. The user must sign in again
  // with the updated username/email or password.
  const handleCredentialsChanged = (message) => {
    localStorage.removeItem("medtrackToken");
    localStorage.removeItem("medtrackRole");
    localStorage.removeItem("medtrackLoggedIn");
    localStorage.removeItem("medtrackUsername");
    localStorage.removeItem("medtrackAdmin");
    setIsAdmin(false);
    setLoggedInUsername("");
    setActivePage("Dashboard");
    setProfileMenuOpen(false);
    setShowSignup(false);
    setAuthNotice(message || "Your account was updated. Please sign in again.");
    setIsLoggedIn(false);
  };

  if (!isLoggedIn) {
    if (showLanding) {
      return (
        <Landing
          onLogin={() => {
            setShowSignup(false);
            setShowLanding(false);
          }}
          onSignup={() => {
            setShowSignup(true);
            setShowLanding(false);
          }}
        />
      );
    }

    return (
      <>
        {showSignup ? (
          <SignupScreen onLoginSuccess={handleLoginSuccess} onBackToLogin={() => setShowSignup(false)} />
        ) : (
          <LoginScreen onLoginSuccess={handleLoginSuccess} onShowSignup={() => setShowSignup(true)} />
        )}
        <AuthToast message={authNotice} onClose={() => setAuthNotice("")} />
      </>
    );
  }

  return (
    <div className="app">
      <AuthToast message={authNotice} onClose={() => setAuthNotice("")} />
      {showLogoutConfirm && <LogoutConfirmModal onCancel={() => setShowLogoutConfirm(false)} onConfirm={handleLogout} />}

      {/* ================= SIDEBAR ================= */}

      <div
        className={`sidebar-backdrop${menuOpen ? " show" : ""}`}
        onClick={() => setMenuOpen(false)}
        aria-hidden="true"
      />

      <aside className={`sidebar${menuOpen ? " open" : ""}`}>

        <div className="logo">

          <div className="logo-icon">
            <Icon name="pill" size={20} />
          </div>

          <div>
            <h2>
              {settings.pharmacyName || "MedTrack"}
            </h2>

            <span>
              Medicine Management
            </span>
          </div>

        </div>

        <div className="nav-caption">Menu</div>

        <nav className="navigation">

          {menuItems.map((item) => (

            <button
              key={item.name}
              className={`nav-item ${
                activePage === item.name ? "active" : ""
              }`}
              onClick={() => handleNavigation(item.name)}
            >

              <span>
                <Icon name={item.icon} />
              </span>

              {item.name}

            </button>

          ))}

        </nav>

        <div className="sidebar-bottom">

          {canOpenSettings && (
          <button
            className={`nav-item ${
              activePage === "Settings" ? "active" : ""
            }`}
            onClick={() => handleNavigation("Settings")}
          >

            <span><Icon name="settings" /></span>
            Settings

          </button>
          )}

          <button
            className="nav-item logout"
            onClick={() => {
              setMenuOpen(false);
              setShowLogoutConfirm(true);
            }}
          >

            <span><Icon name="logout" /></span>
            Logout

          </button>

        </div>

      </aside>

      {/* ================= MAIN CONTENT ================= */}

      <main
        className="main-content"
        style={{
          height: "100vh",
          boxSizing: "border-box",
          overflow: "hidden",
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
        }}
      >

        <header
          className="topbar"
          style={{
            flexShrink: 0,
          }}
        >

          <div className="topbar-left">

            <button
              type="button"
              className="menu-toggle"
              aria-label="Open menu"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen(true)}
            >
              <Icon name="menu" size={22} />
            </button>

            <div className="topbar-title">

              <h1>
                {activePage}
              </h1>

              <p>
                {PAGE_NOTES[activePage] || `Manage your ${activePage.toLowerCase()} here.`}
              </p>

            </div>

          </div>

          <div className="profile">
            <button
              type="button"
              className="profile-trigger"
              aria-label="Open profile menu"
              aria-expanded={profileMenuOpen}
              onClick={() => setProfileMenuOpen((open) => !open)}
            >
              <div className="profile-avatar">
                {(loggedInUsername || "A").trim().charAt(0).toUpperCase() || "A"}
              </div>

              <div className="profile-trigger-details">
                <strong>{loggedInUsername || "Admin"}</strong>
                <span>{ROLE_LABELS[role] || "Staff"}</span>
              </div>

              <span className="profile-chevron" aria-hidden="true">
                {profileMenuOpen ? "⌃" : "⌄"}
              </span>
            </button>

            {profileMenuOpen && (
              <ProfileDropdown
                username={loggedInUsername}
                roleLabel={ROLE_LABELS[role] || "Staff"}
                onViewProfile={() => handleNavigation("My Profile")}
                onChangePassword={() => handleNavigation("Change Password")}
                onLogout={() => {
                  setProfileMenuOpen(false);
                  setShowLogoutConfirm(true);
                }}
                onClose={() => setProfileMenuOpen(false)}
              />
            )}
          </div>

        </header>

        <StatusBanners
          isOwner={role === "owner"}
          onOpenPlan={() => handleNavigation("Plan")}
        />

        {/* ================= PAGES ================= */}

        {activePage === "Dashboard" && (
          <Dashboard
            key={`dashboard-${settingsVersion}`}
            username={loggedInUsername}
            pharmacyName={settings.pharmacyName}
            canEdit={canManageStock}
            onAddMedicine={() =>
              handleNavigation("Add Medicine")
            }
            onViewInventory={() =>
              handleNavigation("Inventory")
            }
            onNewSale={() =>
              handleNavigation("Sales & Billing")
            }
            onViewAlerts={() =>
              handleNavigation("Alerts")
            }
            isOwner={role === "owner"}
            onOpenSettings={() => handleNavigation("Settings")}
            onOpenTeam={() => handleNavigation("Team")}
            onOpenHelp={() => handleNavigation("Help")}
          />
        )}

        {activePage === "Inventory" && (
          <Inventory
            key={`inventory-${settingsVersion}`}
            canEdit={canManageStock}
            onAddMedicine={() =>
              handleNavigation("Add Medicine")
            }
          />
        )}

        {activePage === "Add Medicine" && (
          <AddMedicine
            key={`add-${settingsVersion}`}
            onCancel={() =>
              handleNavigation("Inventory")
            }
            onSuccess={() =>
              handleNavigation("Inventory")
            }
          />
        )}

        {activePage === "Sales & Billing" && (
          <SalesBilling
            key={`sales-${settingsVersion}`}
          />
        )}

        {activePage === "Customers" && (
          <Customers key={`customers-${settingsVersion}`} />
        )}

        {activePage === "Purchases" && canManageStock && (
          <Purchases key={`purchases-${settingsVersion}`} />
        )}

        {activePage === "Activity" && role === "owner" && <Activity />}

        {activePage === "Plan" && role === "owner" && !hidePlan && <PlanPage />}

        {activePage === "Help" && <Help />}

        {activePage === "Admin" && isAdmin && <Admin />}

        {activePage === "Alerts" && (
          <Alerts
            key={`alerts-${settingsVersion}`}
          />
        )}

        {activePage === "Reports" && (
          <Reports
            key={`reports-${settingsVersion}`}
          />
        )}

        {activePage === "Settings" && canOpenSettings && (
          <Settings />
        )}

        {activePage === "Team" && role === "owner" && (
          <Team />
        )}

        {activePage === "My Profile" && (
          <MyProfilePage
            username={loggedInUsername}
            onCredentialsChanged={handleCredentialsChanged}
            onChangePassword={() => handleNavigation("Change Password")}
            onBack={() => handleNavigation("Dashboard")}
          />
        )}

        {activePage === "Change Password" && (
          <ChangePasswordPage
            username={loggedInUsername}
            onCredentialsChanged={handleCredentialsChanged}
            onBack={() => handleNavigation("Dashboard")}
          />
        )}

      </main>

    </div>
  );
}

/* =========================================================
   DASHBOARD
========================================================= */

/* =========================================================
   DASHBOARD
========================================================= */

export default App;
