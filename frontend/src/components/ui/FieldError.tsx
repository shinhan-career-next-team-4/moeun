export function FieldError({ id, message }: { id: string; message: string }) {
  return (
    <p id={id} className="text-sm text-danger">
      {message}
    </p>
  );
}
