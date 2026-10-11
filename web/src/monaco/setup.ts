import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";

// Only the base editor worker is needed: we register a single custom language (mythicskill) with no
// dedicated worker of its own, and never load Monaco's bundled JSON/CSS/HTML/TS language services.
(self as any).MonacoEnvironment = {
  getWorker() {
    return new EditorWorker();
  }
};
