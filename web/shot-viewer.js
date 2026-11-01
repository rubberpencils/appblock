// "See screenshot" buttons open the image full screen, fitted to the
// window, with an X in the top-right corner to close it (also Esc, or a
// tap anywhere outside the image).
(() => {
  const viewer = document.getElementById("shot-viewer");
  if (!viewer || typeof viewer.showModal !== "function") return;
  const img = viewer.querySelector("img");
  for (const button of document.querySelectorAll(".shot-btn")) {
    button.addEventListener("click", () => {
      img.src = button.dataset.src;
      img.alt = button.dataset.alt || "";
      viewer.showModal();
    });
  }
  viewer.querySelector(".shot-viewer__close")
    .addEventListener("click", () => viewer.close());
  // The dialog fills the screen: a click on it, not on the image, is outside.
  viewer.addEventListener("click", (event) => {
    if (event.target === viewer) viewer.close();
  });
})();
