(function () {
  function closeAll() {
    document.querySelectorAll(".email-menu.open").forEach(function (menu) {
      menu.classList.remove("open");
    });
  }

  document.addEventListener("click", function (event) {
    var trigger = event.target.closest(".email-menu__trigger");
    if (trigger) {
      var menu = trigger.parentElement;
      var wasOpen = menu.classList.contains("open");
      closeAll();
      if (!wasOpen) menu.classList.add("open");
      return;
    }

    var copy = event.target.closest(".email-menu__copy");
    if (copy) {
      navigator.clipboard.writeText(copy.dataset.email).then(function () {
        var label = copy.textContent;
        copy.textContent = "Copied!";
        setTimeout(function () {
          copy.textContent = label;
        }, 1000);
      });
      return;
    }

    if (!event.target.closest(".email-menu")) closeAll();
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") closeAll();
  });
})();
