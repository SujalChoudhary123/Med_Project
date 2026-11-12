import { formatMoney } from "../../utils/appUtils.js";

function MedicineDetailsModal({
  medicine,
  onClose,
}) {

  if (!medicine) {
    return null;
  }


  const details = [
    [
      "Medicine ID",
      medicine.id,
    ],
    [
      "Medicine Name",
      medicine.name,
    ],
    [
      "Batch Number",
      medicine.batchNumber ||
        medicine.batch,
    ],
    [
      "Category",
      medicine.category,
    ],
    [
      "Quantity",
      medicine.quantity,
    ],
    [
      "Price",
      formatMoney(medicine.price),
    ],
    [
      "Manufacturer",
      medicine.manufacturer,
    ],
    [
      "Disease",
      medicine.disease,
    ],
    [
      "Manufacturing Date",
      medicine.mfgDate ||
        "—",
    ],
    [
      "Expiry Date",
      medicine.expiryDate ||
        medicine.expiry ||
        "—",
    ],
  ];


  return (

    <div
      style={{
        position: "fixed",
        inset: 0,
        background:
          "rgba(0,0,0,0.45)",
        zIndex: 1000,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "20px",
      }}
      onClick={
        onClose
      }
    >

      <div
        style={{
          width: "min(700px, 100%)",
          maxHeight: "90vh",
          overflowY: "auto",
          background: "#ffffff",
          borderRadius: "16px",
          boxShadow:
            "0 20px 60px rgba(0,0,0,0.2)",
        }}
        onClick={(e) =>
          e.stopPropagation()
        }
      >

        <div
          style={{
            padding: "22px 26px",
            borderBottom:
              "1px solid #e5e7eb",
            display: "flex",
            justifyContent:
              "space-between",
            alignItems: "center",
          }}
        >

          <div>

            <h2
              style={{
                margin: 0,
                fontSize: "22px",
              }}
            >
              Medicine Details
            </h2>

            <p
              style={{
                margin:
                  "5px 0 0",
                color: "#6b7280",
              }}
            >
              Complete medicine information
            </p>

          </div>


          <button
            onClick={
              onClose
            }
            style={{
              border: "none",
              background:
                "#f3f4f6",
              width: "36px",
              height: "36px",
              borderRadius:
                "50%",
              fontSize: "20px",
              cursor: "pointer",
            }}
          >
            ×
          </button>

        </div>


        <div
          style={{
            padding: "26px",
            display: "grid",
            gridTemplateColumns:
              "repeat(2, minmax(0, 1fr))",
            gap: "18px",
          }}
        >

          {details.map(
            ([label, value]) => (

              <div
                key={label}
                style={{
                  padding:
                    "14px 16px",
                  border:
                    "1px solid #e5e7eb",
                  borderRadius:
                    "10px",
                  background:
                    "#fafafa",
                }}
              >

                <div
                  style={{
                    fontSize:
                      "12px",
                    color:
                      "#6b7280",
                    marginBottom:
                      "5px",
                    fontWeight:
                      "600",
                  }}
                >
                  {label}
                </div>

                <div
                  style={{
                    fontSize:
                      "15px",
                    fontWeight:
                      "600",
                    color:
                      "#111827",
                  }}
                >
                  {value}
                </div>

              </div>

            )
          )}

        </div>


        <div
          style={{
            padding:
              "0 26px 24px",
            textAlign:
              "right",
          }}
        >

          <button
            className="primary-button"
            onClick={
              onClose
            }
          >
            Close
          </button>

        </div>

      </div>

    </div>

  );
}


/* =========================================================
   EDIT MEDICINE MODAL
========================================================= */

export default MedicineDetailsModal;
