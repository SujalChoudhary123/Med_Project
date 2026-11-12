

function PagePlaceholder({
  title,
  description,
}) {

  return (

    <div
      className="dashboard-card page-placeholder"
    >

      <div className="empty-icon">
        ◇
      </div>

      <h2>
        {title}
      </h2>

      <p>
        {description}
      </p>

      <span className="coming-soon">
        UI coming next
      </span>

    </div>

  );
}



export default PagePlaceholder;
