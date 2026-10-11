export interface Toast {
  id: number;
  kind: "info" | "success" | "error";
  message: string;
}

type Listener = (toasts: Toast[]) => void;

let toasts: Toast[] = [];
let listeners: Listener[] = [];
let seq = 0;

function emit() {
  for (const l of listeners) l([...toasts]);
}

export function subscribeToasts(fn: Listener): () => void {
  listeners.push(fn);
  fn([...toasts]);
  return () => {
    listeners = listeners.filter((l) => l !== fn);
  };
}

export function pushToast(kind: Toast["kind"], message: string, timeoutMs = 4500) {
  const t = { id: ++seq, kind, message };
  toasts = [...toasts, t];
  emit();
  if (timeoutMs > 0) setTimeout(() => dismissToast(t.id), timeoutMs);
}

export function dismissToast(id: number) {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}
