import Icon from "../layout/Icons.jsx";
import { useEffect, useState } from "react";
import { API_URL, getAppSettings } from "../../utils/appUtils.js";

function Alerts() {

  const [medicines, setMedicines] = useState([]);

  const [loading, setLoading] = useState(true);

  const [error, setError] = useState("");

  const appSettings = getAppSettings();
  const lowStockLimit = Number(appSettings.lowStockLimit);
  const expiryWarningDays = Number(appSettings.expiryWarningDays);

  const [alertFilter, setAlertFilter] =
    useState("All Alerts");

  const [searchText, setSearchText] =
    useState("");


  /* =====================================================
     FETCH MEDICINES
  ===================================================== */

  const fetchMedicines = async () => {

    try {

      setLoading(true);

      setError("");

      const response =
        await fetch(API_URL);

      if (!response.ok) {

        throw new Error(
          `Server returned ${response.status}`
        );

      }

      const data =
        await response.json();

      setMedicines(
        Array.isArray(data)
          ? data
          : []
      );

    } catch (err) {

      console.error(
        "Alerts fetch error:",
        err
      );

      setError(
        "Unable to load alerts. Make sure the Java API server is running."
      );

      setMedicines([]);

    } finally {

      setLoading(false);

    }

  };


  /* =====================================================
     INITIAL LOAD
  ===================================================== */

  useEffect(() => {
    const timer = setTimeout(() => fetchMedicines(), 0);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    const handleRefresh = () => fetchMedicines();
    window.addEventListener("medtrack-auto-refresh", handleRefresh);
    return () => window.removeEventListener("medtrack-auto-refresh", handleRefresh);
  }, []);


  /* =====================================================
     DATE HELPERS
  ===================================================== */

  const getToday = () => {

    const today =
      new Date();

    today.setHours(
      0,
      0,
      0,
      0
    );

    return today;

  };


  const getExpiryDate = (medicine) => {

    const expiryValue =
      medicine.expiryDate ||
      medicine.expiry;

    if (!expiryValue) {

      return null;

    }


    const expiryDate =
      new Date(
        `${String(expiryValue)}T00:00:00`
      );


    if (
      Number.isNaN(
        expiryDate.getTime()
      )
    ) {

      return null;

    }


    return expiryDate;

  };


  /* =====================================================
     ALERT CALCULATIONS
  ===================================================== */

  const today =
    getToday();


  const thirtyDaysFromNow =
    new Date(today);


  thirtyDaysFromNow.setDate(
    today.getDate() + expiryWarningDays
  );


  const expiredMedicines =
    medicines.filter(
      (medicine) => {

        const expiryDate =
          getExpiryDate(
            medicine
          );

        return (
          expiryDate &&
          expiryDate < today
        );

      }
    );


  const expiringSoonMedicines =
    medicines.filter(
      (medicine) => {

        const expiryDate =
          getExpiryDate(
            medicine
          );

        return (
          expiryDate &&
          expiryDate >= today &&
          expiryDate <=
            thirtyDaysFromNow
        );

      }
    );


  const lowStockMedicines =
    medicines.filter(
      (medicine) =>
        Number(
          medicine.quantity
        ) <= lowStockLimit
    );


  /* =====================================================
     TOTAL ALERTS
  ===================================================== */

  const totalAlerts =
    expiredMedicines.length +
    expiringSoonMedicines.length +
    lowStockMedicines.length;


  /* =====================================================
     BUILD ALERT LIST
  ===================================================== */

  const allAlerts =
    medicines
      .map((medicine) => {

        const quantity =
          Number(
            medicine.quantity
          );


        const expiryDate =
          getExpiryDate(
            medicine
          );


        const isExpired =
          expiryDate &&
          expiryDate < today;


        const isExpiringSoon =
          expiryDate &&
          expiryDate >= today &&
          expiryDate <=
            thirtyDaysFromNow;


        const isLowStock =
          quantity <= lowStockLimit;


        let alertType;

        let severity;


        if (isExpired) {

          alertType =
            "Expired";

          severity =
            "critical";

        } else if (
          isLowStock &&
          isExpiringSoon
        ) {

          alertType =
            "Low Stock + Expiring";

          severity =
            "critical";

        } else if (
          isLowStock
        ) {

          alertType =
            "Low Stock";

          severity =
            "warning";

        } else if (
          isExpiringSoon
        ) {

          alertType =
            "Expiring Soon";

          severity =
            "warning";

        } else {

          return null;

        }


        let daysLeft =
          null;


        if (expiryDate) {

          daysLeft =
            Math.ceil(
              (
                expiryDate.getTime() -
                today.getTime()
              ) /
              (
                1000 *
                60 *
                60 *
                24
              )
            );

        }


        return {

          ...medicine,

          quantity,

          expiryDate,

          alertType,

          severity,

          daysLeft,

        };

      })
      .filter(Boolean);


  /* =====================================================
     SEARCH + FILTER
  ===================================================== */

  const filteredAlerts =
    allAlerts.filter(
      (medicine) => {

        const search =
          searchText
            .trim()
            .toLowerCase();


        const matchesSearch =
          !search ||

          String(
            medicine.id || ""
          )
            .toLowerCase()
            .includes(search) ||

          String(
            medicine.name || ""
          )
            .toLowerCase()
            .includes(search) ||

          String(
            medicine.batchNumber ||
            medicine.batch ||
            ""
          )
            .toLowerCase()
            .includes(search) ||

          String(
            medicine.manufacturer ||
            ""
          )
            .toLowerCase()
            .includes(search);


        if (!matchesSearch) {

          return false;

        }


        if (
          alertFilter ===
          "All Alerts"
        ) {

          return true;

        }


        if (
          alertFilter ===
          "Expired"
        ) {

          return (
            medicine.alertType ===
            "Expired"
          );

        }


        if (
          alertFilter ===
          "Low Stock"
        ) {

          return (
            medicine.quantity <= lowStockLimit
          );

        }


        if (
          alertFilter ===
          "Expiring Soon"
        ) {

          return (
            medicine.daysLeft !==
              null &&
            medicine.daysLeft >=
              0 &&
            medicine.daysLeft <=
              30
          );

        }


        return true;

      }
    );


  /* =====================================================
     DATE FORMAT
     
     IMPORTANT FIX:
     medicine.expiryDate is already a Date object
     inside allAlerts. React cannot render a Date
     object directly.
  ===================================================== */

  const formatDate = (dateValue) => {

    if (!dateValue) {

      return "—";

    }


    let date;


    if (
      dateValue instanceof Date
    ) {

      date =
        dateValue;

    } else {

      date =
        new Date(
          `${String(dateValue)}T00:00:00`
        );

    }


    if (
      Number.isNaN(
        date.getTime()
      )
    ) {

      return String(
        dateValue
      );

    }


    return date.toLocaleDateString(
      "en-IN",
      {
        day: "2-digit",
        month: "short",
        year: "numeric",
      }
    );

  };


  /* =====================================================
     DAYS LEFT TEXT
  ===================================================== */

  const getExpiryText = (
    medicine
  ) => {

    if (
      medicine.daysLeft ===
      null
    ) {

      return "—";

    }


    if (
      medicine.daysLeft < 0
    ) {

      const days =
        Math.abs(
          medicine.daysLeft
        );


      return `${days} day${
        days === 1
          ? ""
          : "s"
      } ago`;

    }


    if (
      medicine.daysLeft === 0
    ) {

      return "Expires today";

    }


    return `${medicine.daysLeft} day${
      medicine.daysLeft === 1
        ? ""
        : "s"
    } left`;

  };


  /* =====================================================
     REFRESH
  ===================================================== */

  const handleRefresh = () => {

    fetchMedicines();

  };


  /* =====================================================
     RENDER
  ===================================================== */

  return (

    <div
      style={{
        flex: 1,
        minHeight: 0,
        overflowY: "auto",
        paddingBottom: "25px",
      }}
    >

      {/* ================= ERROR ================= */}

      {error && (

        <div
          style={{
            marginBottom: "18px",
            padding: "12px 16px",
            borderRadius: "8px",
            background: "#fef2f2",
            color: "#b91c1c",
            border:
              "1px solid #fecaca",
            fontSize: "14px",
          }}
        >

          {error}

        </div>

      )}


      {/* ================= SUMMARY CARDS ================= */}

      <section className="stats-grid">

        <div className="stat-card">

          <div className="stat-icon red">
            <Icon name="cross" size={21} />
          </div>

          <div>

            <span>
              Expired
            </span>

            <strong>
              {loading
                ? "..."
                : expiredMedicines.length}
            </strong>

            <small>
              Medicines past expiry
            </small>

          </div>

        </div>


        <div className="stat-card">

          <div className="stat-icon orange">
            <Icon name="clock" size={21} />
          </div>

          <div>

            <span>
              Expiring Soon
            </span>

            <strong>
              {loading
                ? "..."
                : expiringSoonMedicines.length}
            </strong>

            <small>
              {`Within ${expiryWarningDays} days`}
            </small>

          </div>

        </div>


        <div className="stat-card">

          <div className="stat-icon orange">
            <Icon name="alert" size={21} />
          </div>

          <div>

            <span>
              Low Stock
            </span>

            <strong>
              {loading
                ? "..."
                : lowStockMedicines.length}
            </strong>

            <small>
              {`Quantity ${lowStockLimit} or below`}
            </small>

          </div>

        </div>


        <div className="stat-card">

          <div className="stat-icon blue">
            <Icon name="bell" size={21} />
          </div>

          <div>

            <span>
              Total Alerts
            </span>

            <strong>
              {loading
                ? "..."
                : totalAlerts}
            </strong>

            <small>
              Items requiring attention
            </small>

          </div>

        </div>

      </section>


      {/* ================= MAIN ALERT CARD ================= */}

      <div
        className="dashboard-card"
        style={{
          marginTop: "20px",
        }}
      >

        {/* ================= HEADER ================= */}

        <div
          className="card-header"
          style={{
            flexWrap: "wrap",
            gap: "12px",
          }}
        >

          <div>

            <h2>
              Inventory Alerts
            </h2>

            <p>
              Medicines that require your attention
            </p>

          </div>


          <button
            className="refresh-button"
            onClick={
              handleRefresh
            }
            disabled={loading}
          >

            ↻ Refresh

          </button>

        </div>


        {/* ================= SEARCH + FILTER ================= */}

        <div
          style={{
            display: "flex",
            gap: "10px",
            flexWrap: "wrap",
            marginBottom: "18px",
          }}
        >

          <div
            className="search-box"
            style={{
              flex: "1",
              minWidth: "220px",
            }}
          >

            <span>
              ⌕
            </span>

            <input
              type="text"
              placeholder="Search medicine, ID, batch..."
              value={searchText}
              onChange={(e) =>
                setSearchText(
                  e.target.value
                )
              }
            />

          </div>


          <select
            value={alertFilter}
            onChange={(e) =>
              setAlertFilter(
                e.target.value
              )
            }
            style={{
              minWidth: "170px",
              padding:
                "10px 12px",
              borderRadius: "8px",
              border:
                "1px solid #d1d5db",
              background:
                "#ffffff",
              fontSize: "14px",
              color:
                "#374151",
            }}
          >

            <option>
              All Alerts
            </option>

            <option>
              Expired
            </option>

            <option>
              Expiring Soon
            </option>

            <option>
              Low Stock
            </option>

          </select>

        </div>


        {/* ================= LOADING ================= */}

        {loading && (

          <div
            style={{
              padding:
                "40px 20px",
              textAlign:
                "center",
              color:
                "#6b7280",
            }}
          >

            Loading alerts...

          </div>

        )}


        {/* ================= ALL CLEAR ================= */}

        {!loading &&
          filteredAlerts.length === 0 && (

            <div
              style={{
                padding:
                  "45px 20px",
                textAlign:
                  "center",
                background:
                  "#ecfdf5",
                border:
                  "1px solid #a7f3d0",
                borderRadius:
                  "10px",
              }}
            >

              <div
                style={{
                  fontSize:
                    "34px",
                  marginBottom:
                    "10px",
                }}
              >
                ✓
              </div>


              <h3
                style={{
                  margin:
                    "0 0 6px",
                  color:
                    "#047857",
                }}
              >
                No Alerts Found
              </h3>


              <p
                style={{
                  margin: 0,
                  color:
                    "#6b7280",
                  fontSize:
                    "14px",
                }}
              >

                {searchText ||
                alertFilter !==
                  "All Alerts"
                  ? "No medicines match the selected search or filter."
                  : "Your inventory currently has no urgent alerts."}

              </p>

            </div>

          )}


        {/* ================= ALERT TABLE ================= */}

        {!loading &&
          filteredAlerts.length > 0 && (

            <div
              style={{
                overflowX:
                  "auto",
              }}
            >

              <table
                style={{
                  width: "100%",
                  borderCollapse:
                    "collapse",
                  fontSize: "13px",
                }}
              >

                <thead>

                  <tr
                    style={{
                      borderBottom:
                        "1px solid #e5e7eb",
                    }}
                  >

                    <th
                      style={{
                        textAlign:
                          "left",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Medicine
                    </th>


                    <th
                      style={{
                        textAlign:
                          "left",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Batch
                    </th>


                    <th
                      style={{
                        textAlign:
                          "center",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Stock
                    </th>


                    <th
                      style={{
                        textAlign:
                          "left",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Expiry
                    </th>


                    <th
                      style={{
                        textAlign:
                          "left",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Alert
                    </th>


                    <th
                      style={{
                        textAlign:
                          "left",
                        padding:
                          "11px 8px",
                        color:
                          "#6b7280",
                        fontWeight:
                          "600",
                      }}
                    >
                      Details
                    </th>

                  </tr>

                </thead>


                <tbody>

                  {filteredAlerts.map(
                    (medicine) => (

                      <tr
                        key={
                          medicine.id
                        }
                        style={{
                          borderBottom:
                            "1px solid #f0f0f0",
                        }}
                      >

                        {/* MEDICINE */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                          }}
                        >

                          <div
                            style={{
                              fontWeight:
                                "600",
                              color:
                                "#111827",
                            }}
                          >
                            {medicine.name}
                          </div>


                          <div
                            style={{
                              fontSize:
                                "11px",
                              color:
                                "#9ca3af",
                              marginTop:
                                "2px",
                            }}
                          >
                            ID:{" "}
                            {medicine.id}
                          </div>

                        </td>


                        {/* BATCH */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                          }}
                        >

                          {medicine.batchNumber ||
                            medicine.batch ||
                            "—"}

                        </td>


                        {/* STOCK */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                            textAlign:
                              "center",
                          }}
                        >

                          <span
                            className={
                              medicine.quantity <=
                              10
                                ? "quantity-badge low"
                                : "quantity-badge"
                            }
                          >

                            {medicine.quantity}

                          </span>

                        </td>


                        {/* EXPIRY */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                          }}
                        >

                          <div
                            style={{
                              fontWeight:
                                "600",
                              color:
                                medicine.daysLeft !==
                                  null &&
                                medicine.daysLeft <
                                  0
                                  ? "#b91c1c"
                                  : "#374151",
                            }}
                          >

                            {formatDate(
                              medicine.expiryDate
                            )}

                          </div>


                          <div
                            style={{
                              fontSize:
                                "11px",
                              marginTop:
                                "3px",
                              color:
                                medicine.daysLeft !==
                                  null &&
                                medicine.daysLeft <
                                  0
                                  ? "#b91c1c"
                                  : "#6b7280",
                            }}
                          >

                            {getExpiryText(
                              medicine
                            )}

                          </div>

                        </td>


                        {/* ALERT */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                          }}
                        >

                          <span
                            style={{
                              display:
                                "inline-block",
                              padding:
                                "5px 9px",
                              borderRadius:
                                "999px",
                              fontSize:
                                "11px",
                              fontWeight:
                                "700",

                              background:
                                medicine.severity ===
                                "critical"
                                  ? "#fef2f2"
                                  : "#fff7ed",

                              color:
                                medicine.severity ===
                                "critical"
                                  ? "#b91c1c"
                                  : "#c2410c",

                              border:
                                medicine.severity ===
                                "critical"
                                  ? "1px solid #fecaca"
                                  : "1px solid #fed7aa",
                            }}
                          >

                            {medicine.alertType}

                          </span>

                        </td>


                        {/* DETAILS */}

                        <td
                          style={{
                            padding:
                              "13px 8px",
                            color:
                              "#6b7280",
                          }}
                        >

                          {medicine.quantity <=
                            10 &&
                          medicine.daysLeft !==
                            null &&
                          medicine.daysLeft >=
                            0 &&
                          medicine.daysLeft <=
                            30

                            ? "Restock and monitor expiry"

                            : medicine.quantity <=
                              10

                              ? "Restock required"

                              : medicine.daysLeft !==
                                  null &&
                                medicine.daysLeft <
                                  0

                                ? "Remove from active stock"

                                : "Expiry approaching"}

                        </td>

                      </tr>

                    )
                  )}

                </tbody>

              </table>

            </div>

          )}

      </div>

    </div>

  );

}
/* =========================================================
   REPORTS
========================================================= */

export default Alerts;
