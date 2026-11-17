import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import NavigateBridge from "./NavigateBridge";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <NavigateBridge />
      <App />
    </BrowserRouter>
  </StrictMode>,
);
