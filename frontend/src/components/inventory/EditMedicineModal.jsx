import { useState } from "react";
import { API_URL } from "../../utils/appUtils.js";
import EditField from "./EditField.jsx";
import GstFields from "./GstFields.jsx";

function EditMedicineModal({
  medicine,
  onClose,
  onSuccess,
}) {

  const [formData, setFormData] =
    useState({
      name:
        medicine?.name || "",

      batchNumber:
        medicine?.batchNumber ||
        medicine?.batch ||
        "",

      category:
        medicine?.category || "",

      quantity:
        medicine?.quantity ?? "",

      price:
        medicine?.price ?? "",

      manufacturer:
        medicine?.manufacturer ||
        "",

      disease:
        medicine?.disease ||
        "",

      mfgDate:
        medicine?.mfgDate ||
        "",

      expiryDate:
        medicine?.expiryDate ||
        medicine?.expiry ||
        "",

      gstRate:
        String(medicine?.gstRate ?? 5),

      hsn:
        medicine?.hsn || "3004",

      costPrice: "",
    });


  const [loading, setLoading] =
    useState(false);

  const [error, setError] =
    useState("");

  const [success, setSuccess] =
    useState("");


  const handleChange = (e) => {

    const {
      name,
      value,
    } = e.target;


    setFormData(
      (previous) => ({
        ...previous,
        [name]: value,
      })
    );


    setError("");
    setSuccess("");
  };


  const handleSubmit =
    async (e) => {

      e.preventDefault();

      setError("");
      setSuccess("");


      if (
        !formData.name.trim() ||
        !formData.batchNumber.trim() ||
        !formData.category.trim() ||
        formData.quantity === "" ||
        formData.price === "" ||
        !formData.manufacturer.trim() ||
        !formData.disease.trim() ||
        !formData.mfgDate ||
        !formData.expiryDate
      ) {

        setError(
          "Please fill in all fields."
        );

        return;
      }


      const quantity =
        Number(
          formData.quantity
        );

      const price =
        Number(
          formData.price
        );


      if (
        !Number.isInteger(
          quantity
        ) ||
        quantity < 0
      ) {

        setError(
          "Quantity must be a whole number and cannot be negative."
        );

        return;
      }


      if (
        Number.isNaN(
          price
        ) ||
        price < 0
      ) {

        setError(
          "Price must be a valid number and cannot be negative."
        );

        return;
      }


      if (
        formData.expiryDate <
        formData.mfgDate
      ) {

        setError(
          "Expiry date cannot be before manufacturing date."
        );

        return;
      }


      try {

        setLoading(true);


        const response =
          await fetch(
            `${API_URL}/${encodeURIComponent(
              medicine.id
            )}`,
            {
              method: "PUT",

              headers: {
                "Content-Type":
                  "application/json",
              },

              body:
                JSON.stringify({
                  name:
                    formData.name.trim(),

                  batchNumber:
                    formData.batchNumber.trim(),

                  category:
                    formData.category.trim(),

                  quantity:
                    quantity,

                  price:
                    price,

                  manufacturer:
                    formData.manufacturer.trim(),

                  disease:
                    formData.disease.trim(),

                  mfgDate:
                    formData.mfgDate,

                  expiryDate:
                    formData.expiryDate,

                  gstRate:
                    formData.gstRate,

                  hsn:
                    String(formData.hsn).trim(),

                  costPrice:
                    formData.costPrice,
                }),
            }
          );


        const responseText =
          await response.text();


        let result = {};

        try {

          result =
            JSON.parse(
              responseText
            );

        } catch {

          result = {};

        }


        if (!response.ok) {

          throw new Error(
            result.error ||
            "Medicine could not be updated."
          );

        }


        setSuccess(
          "Medicine updated successfully!"
        );


        setTimeout(
          () => {
            onSuccess();
          },
          500
        );


      } catch (err) {

        console.error(
          "Update error:",
          err
        );

        setError(
          err.message ||
          "Unable to update medicine."
        );

      } finally {

        setLoading(false);

      }

    };


  if (!medicine) {
    return null;
  }


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
    >

      <div
        style={{
          width:
            "min(850px, 100%)",
          maxHeight:
            "90vh",
          overflowY:
            "auto",
          background:
            "#ffffff",
          borderRadius:
            "16px",
          boxShadow:
            "0 20px 60px rgba(0,0,0,0.2)",
        }}
      >

        <div
          style={{
            padding:
              "22px 26px",
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
                fontSize:
                  "22px",
              }}
            >
              Edit Medicine
            </h2>

            <p
              style={{
                margin:
                  "5px 0 0",
                color:
                  "#6b7280",
              }}
            >
              Editing {medicine.id}
            </p>

          </div>


          <button
            onClick={
              onClose
            }
            disabled={
              loading
            }
            style={{
              border: "none",
              background:
                "#f3f4f6",
              width: "36px",
              height: "36px",
              borderRadius:
                "50%",
              fontSize:
                "20px",
              cursor:
                "pointer",
            }}
          >
            ×
          </button>

        </div>


        <form
          onSubmit={
            handleSubmit
          }
          style={{
            padding:
              "26px",
          }}
        >

          {error && (

            <div
              style={{
                marginBottom:
                  "18px",
                padding:
                  "12px 15px",
                borderRadius:
                  "8px",
                background:
                  "#fef2f2",
                color:
                  "#b91c1c",
                border:
                  "1px solid #fecaca",
              }}
            >
              {error}
            </div>

          )}


          {success && (

            <div
              style={{
                marginBottom:
                  "18px",
                padding:
                  "12px 15px",
                borderRadius:
                  "8px",
                background:
                  "#ecfdf5",
                color:
                  "#047857",
                border:
                  "1px solid #a7f3d0",
              }}
            >
              {success}
            </div>

          )}


          <div
            style={{
              display: "grid",
              gridTemplateColumns:
                "repeat(2, minmax(0, 1fr))",
              gap: "18px",
            }}
          >

            <EditField
              label="Medicine ID"
              value={
                medicine.id
              }
              disabled
            />


            <EditField
              label="Medicine Name"
              name="name"
              value={
                formData.name
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Batch Number"
              name="batchNumber"
              value={
                formData.batchNumber
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Category"
              name="category"
              value={
                formData.category
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Quantity"
              name="quantity"
              type="number"
              min="0"
              step="1"
              value={
                formData.quantity
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Price"
              name="price"
              type="number"
              min="0"
              step="0.01"
              value={
                formData.price
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Manufacturer"
              name="manufacturer"
              value={
                formData.manufacturer
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Disease"
              name="disease"
              value={
                formData.disease
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Manufacturing Date"
              name="mfgDate"
              type="date"
              value={
                formData.mfgDate
              }
              onChange={
                handleChange
              }
            />


            <EditField
              label="Expiry Date"
              name="expiryDate"
              type="date"
              value={
                formData.expiryDate
              }
              onChange={
                handleChange
              }
            />


            <GstFields
              gstRate={
                formData.gstRate
              }
              hsn={
                formData.hsn
              }
              costPrice={
                formData.costPrice
              }
              costHint="Leave empty to keep the current cost."
              onChange={
                handleChange
              }
            />

          </div>


          <div
            style={{
              display: "flex",
              justifyContent:
                "flex-end",
              gap: "10px",
              marginTop:
                "26px",
              paddingTop:
                "20px",
              borderTop:
                "1px solid #e5e7eb",
            }}
          >

            <button
              type="button"
              onClick={
                onClose
              }
              disabled={
                loading
              }
              style={{
                padding:
                  "11px 22px",
                borderRadius:
                  "8px",
                border:
                  "1px solid #d1d5db",
                background:
                  "#ffffff",
                cursor:
                  "pointer",
                fontWeight:
                  "600",
              }}
            >
              Cancel
            </button>


            <button
              type="submit"
              disabled={
                loading
              }
              style={{
                padding:
                  "11px 24px",
                borderRadius:
                  "8px",
                border:
                  "none",
                background:
                  loading
                    ? "#6ee7b7"
                    : "#059669",
                color:
                  "#ffffff",
                cursor:
                  loading
                    ? "not-allowed"
                    : "pointer",
                fontWeight:
                  "600",
              }}
            >
              {loading
                ? "Saving..."
                : "Save Changes"}
            </button>

          </div>

        </form>

      </div>

    </div>

  );
}


/* =========================================================
   EDIT FIELD
========================================================= */

export default EditMedicineModal;
