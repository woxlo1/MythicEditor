import { h } from "preact";
import { useEffect, useState } from "preact/hooks";
import { subscribeToasts, dismissToast, type Toast } from "../lib/toast";

export function Toasts() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  useEffect(() => subscribeToasts(setToasts), []);
  return (
    <div class="toast-stack">
      {toasts.map((t) => (
        <div class={"toast " + t.kind} key={t.id} onClick={() => dismissToast(t.id)}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
