import { useEffect, useState } from "react";
import { API_URL, formatMoney, isLowStock } from "../../utils/appUtils.js";
import MedicineDetailsModal from "./MedicineDetailsModal.jsx";
import EditMedicineModal from "./EditMedicineModal.jsx";

function Inventory({
  onAddMedicine,
  canEdit = true,
}) {

  const [medicines, setMedicines] =
    useState([]);

  const [searchText, setSearchText] =
    useState("");

  const [filterBy, setFilterBy] =
    useState("Name");

  const [loading, setLoading] =
    useState(true);

  const [error, setError] =
    useState("");

  const [selectedMedicine, setSelectedMedicine] =
    useState(null);

  const [modalType, setModalType] =
    useState(null);


  /* =====================================================
     FETCH MEDICINES
  ===================================================== */

  const fetchMedicines = async (
    search = "",
    filter = "Name"
  ) => {

    try {

      setLoading(true);

      setError("");

      const params =
        new URLSearchParams();


      if (search.trim()) {

        params.set(
          "search",
          search.trim()
        );

      }


      params.set(
        "filter",
        filter
      );


      const response =
        await fetch(
          `${API_URL}?${params.toString()}`
        );


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
        "Medicine fetch error:",
        err
      );

      setError(
        "Unable to connect to the Java API. Make sure ApiServer is running."
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
    const timer = setTimeout(() => fetchMedicines("", "Name"), 0);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    const handleRefresh = () => fetchMedicines(searchText, filterBy);
    window.addEventListener("medtrack-auto-refresh", handleRefresh);
    return () => window.removeEventListener("medtrack-auto-refresh", handleRefresh);
  }, [searchText, filterBy]);


  /* =====================================================
     LIVE SEARCH
  ===================================================== */

  useEffect(() => {

    const timer =
      setTimeout(() => {

        fetchMedicines(
          searchText,
          filterBy
        );

      }, 250);


    return () => {

      clearTimeout(timer);

    };

  }, [
    searchText,
    filterBy,
  ]);


  /* =====================================================
     REFRESH
  ===================================================== */

  const handleRefresh = () => {

    setSearchText("");

    setFilterBy("Name");

    setSelectedMedicine(null);

  };


  /* =====================================================
     SELECT ROW
  ===================================================== */

  const handleSelectMedicine = (
    medicine
  ) => {

    setSelectedMedicine(
      medicine
    );

  };


  /* =====================================================
     VIEW DETAILS
  ===================================================== */

  const handleViewDetails = async () => {

    if (!selectedMedicine) {

      alert(
        "Please select one medicine first."
      );

      return;
    }


    try {

      setError("");


      const response =
        await fetch(
          `${API_URL}/${encodeURIComponent(
            selectedMedicine.id
          )}`
        );


      if (!response.ok) {

        throw new Error(
          "Medicine details could not be loaded."
        );

      }


      const data =
        await response.json();


      setSelectedMedicine(
        data
      );

      setModalType(
        "view"
      );


    } catch (err) {

      console.error(err);

      setError(
        err.message ||
        "Unable to load medicine details."
      );

    }

  };


  /* =====================================================
     EDIT MEDICINE
  ===================================================== */

  const handleEdit = async () => {

    if (!selectedMedicine) {

      alert(
        "Please select one medicine first."
      );

      return;
    }


    try {

      setError("");


      const response =
        await fetch(
          `${API_URL}/${encodeURIComponent(
            selectedMedicine.id
          )}`
        );


      if (!response.ok) {

        throw new Error(
          "Medicine details could not be loaded."
        );

      }


      const data =
        await response.json();


      setSelectedMedicine(
        data
      );

      setModalType(
        "edit"
      );


    } catch (err) {

      console.error(err);

      setError(
        err.message ||
        "Unable to load medicine details."
      );

    }

  };


  /* =====================================================
     DELETE MEDICINE
  ===================================================== */

  const handleDelete = async () => {

    if (!selectedMedicine) {

      alert(
        "Please select one medicine first."
      );

      return;
    }


    const confirmed =
      window.confirm(
        `Are you sure you want to delete ${selectedMedicine.name} (${selectedMedicine.id})?\n\nThis action cannot be undone.`
      );


    if (!confirmed) {
      return;
    }


    try {

      setError("");


      const response =
        await fetch(
          `${API_URL}/${encodeURIComponent(
            selectedMedicine.id
          )}`,
          {
            method: "DELETE",
          }
        );


      const result =
        await response.json();


      if (!response.ok) {

        throw new Error(
          result.error ||
          "Medicine could not be deleted."
        );

      }


      setSelectedMedicine(
        null
      );


      setModalType(
        null
      );


      await fetchMedicines(
        searchText,
        filterBy
      );


      alert(
        "Medicine deleted successfully."
      );


    } catch (err) {

      console.error(
        "Delete error:",
        err
      );

      setError(
        err.message ||
        "Unable to delete medicine."
      );

    }

  };


  /* =====================================================
     CLOSE MODAL
  ===================================================== */

  const closeModal = () => {

    setModalType(
      null
    );

  };


  /* =====================================================
     AFTER EDIT
  ===================================================== */

  const handleEditSuccess = async () => {

    setModalType(
      null
    );

    setSelectedMedicine(
      null
    );


    await fetchMedicines(
      searchText,
      filterBy
    );

  };


  return (
    <div
      className="inventory-page"
      style={{
        flex: 1,
        minHeight: 0,
        height: "auto",
        display: "flex",
        flexDirection: "column",
        overflow: "hidden",
      }}
    >

      {/* ================= TOOLBAR ================= */}

      <div
        className="inventory-toolbar"
        style={{
          flexShrink: 0,
        }}
      >

        <div className="search-area">

          <div className="search-box">

            <span>
              ⌕
            </span>

            <input
              type="text"
              placeholder="Search medicines..."
              value={searchText}
              onChange={(e) =>
                setSearchText(
                  e.target.value
                )
              }
            />

          </div>


          <select
            value={filterBy}
            onChange={(e) =>
              setFilterBy(
                e.target.value
              )
            }
          >

            <option value="Medicine ID">
              Medicine ID
            </option>

            <option value="Name">
              Name
            </option>

            <option value="Disease">
              Disease
            </option>

            <option value="Manufacturer">
              Manufacturer
            </option>

            <option value="Category">
              Category
            </option>

          </select>

        </div>


        {canEdit && (
        <button
          className="add-medicine-button"
          onClick={
            onAddMedicine
          }
        >
          + Add Medicine
        </button>
        )}

      </div>


      {/* ================= INVENTORY CARD ================= */}

      <div
        className="inventory-card"
        style={{
          flex: 1,
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
        }}
      >

        <div
          className="inventory-card-header"
          style={{
            flexShrink: 0,
          }}
        >

          <div>

            <h2>
              Medicine Inventory
            </h2>

            <p>
              Manage medicines, stock and expiry information.
            </p>

          </div>


          <button
            className="refresh-button"
            onClick={
              handleRefresh
            }
          >
            ↻ Refresh
          </button>

        </div>


        {/* ================= ERROR ================= */}

        {error && (

          <div
            style={{
              margin: "12px 20px",
              padding: "12px 16px",
              borderRadius: "8px",
              background: "#fef2f2",
              color: "#b91c1c",
              border: "1px solid #fecaca",
              flexShrink: 0,
            }}
          >
            {error}
          </div>

        )}


        {/* ================= TABLE ================= */}

        <div
          className="inventory-table-wrapper"
          style={{
            flex: 1,
            minHeight: 0,
            overflowY: "auto",
            overflowX: "auto",
          }}
        >

          <table className="inventory-table">

            <thead>

              <tr>

                <th>
                  ID
                </th>

                <th>
                  Medicine
                </th>

                <th>
                  Batch
                </th>

                <th>
                  Category
                </th>

                <th>
                  Quantity
                </th>

                <th>
                  Price
                </th>

                <th>
                  Manufacturer
                </th>

                <th>
                  Disease
                </th>

                <th>
                  Expiry
                </th>

                <th>
                  GST
                </th>

              </tr>

            </thead>


            <tbody>

              {loading ? (

                <tr>

                  <td
                    colSpan="10"
                    style={{
                      textAlign:
                        "center",
                      padding:
                        "30px",
                    }}
                  >
                    Loading medicines...
                  </td>

                </tr>

              ) : medicines.length === 0 ? (

                <tr>

                  <td
                    colSpan="10"
                    style={{
                      textAlign:
                        "center",
                      padding:
                        "30px",
                    }}
                  >
                    {searchText
                      ? "No medicines found."
                      : "No medicines in inventory."}
                  </td>

                </tr>

              ) : (

                medicines.map(
                  (medicine) => {

                    const isSelected =
                      selectedMedicine?.id ===
                      medicine.id;


                    return (

                      <tr
                        key={
                          medicine.id
                        }
                        onClick={() =>
                          handleSelectMedicine(
                            medicine
                          )
                        }
                        style={{
                          cursor:
                            "pointer",

                          background:
                            isSelected
                              ? "#ecfdf5"
                              : undefined,

                          boxShadow:
                            isSelected
                              ? "inset 3px 0 0 #059669"
                              : undefined,
                        }}
                      >

                        <td className="medicine-id">
                          {medicine.id}
                        </td>


                        <td className="medicine-name">
                          {medicine.name}
                        </td>


                        <td>
                          {medicine.batch}
                        </td>


                        <td>

                          <span className="category-badge">
                            {medicine.category}
                          </span>

                        </td>


                        <td>

                          <span
                            className={
                              isLowStock(medicine.quantity)
                                ? "quantity-badge low"
                                : "quantity-badge"
                            }
                          >
                            {
                              medicine.quantity
                            }
                          </span>

                        </td>


                        <td>
                          {formatMoney(medicine.price)}
                        </td>


                        <td>
                          {
                            medicine.manufacturer
                          }
                        </td>


                        <td>
                          {
                            medicine.disease
                          }
                        </td>


                        <td>
                          {
                            medicine.expiry
                          }
                        </td>


                        <td>
                          {medicine.gstRate ?? 5}%
                        </td>

                      </tr>

                    );

                  }
                )

              )}

            </tbody>

          </table>

        </div>


        {/* ================= FOOTER ================= */}

        <div
          className="inventory-footer"
          style={{
            flexShrink: 0,
          }}
        >

          <span>

            {selectedMedicine
              ? `Selected: ${selectedMedicine.name}`
              : `Showing ${medicines.length} medicines`}

          </span>


          <div
            className="inventory-actions"
            style={{
              display: "flex",
              gap: "8px",
            }}
          >

            <button
              className="secondary-button"
              disabled={
                !selectedMedicine
              }
              onClick={
                handleViewDetails
              }
              style={{
                opacity:
                  selectedMedicine
                    ? 1
                    : 0.5,

                cursor:
                  selectedMedicine
                    ? "pointer"
                    : "not-allowed",
              }}
            >
              View Details
            </button>


        {canEdit && (
            <button
              className="secondary-button"
              disabled={
                !selectedMedicine
              }
              onClick={
                handleEdit
              }
              style={{
                opacity:
                  selectedMedicine
                    ? 1
                    : 0.5,

                cursor:
                  selectedMedicine
                    ? "pointer"
                    : "not-allowed",
              }}
            >
              Edit Selected
            </button>
        )}


        {canEdit && (
            <button
              className="delete-button"
              disabled={
                !selectedMedicine
              }
              onClick={
                handleDelete
              }
              style={{
                opacity:
                  selectedMedicine
                    ? 1
                    : 0.5,

                cursor:
                  selectedMedicine
                    ? "pointer"
                    : "not-allowed",
              }}
            >
              Delete Selected
            </button>
        )}

          </div>

        </div>

      </div>


      {/* ================= MODALS ================= */}

      {modalType === "view" && (
        <MedicineDetailsModal
          medicine={
            selectedMedicine
          }
          onClose={
            closeModal
          }
        />
      )}


      {modalType === "edit" && (
        <EditMedicineModal
          medicine={
            selectedMedicine
          }
          onClose={
            closeModal
          }
          onSuccess={
            handleEditSuccess
          }
        />
      )}

    </div>
  );
}


/* =========================================================
   VIEW DETAILS MODAL
========================================================= */

export default Inventory;
