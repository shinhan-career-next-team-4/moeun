import { Button, Modal } from "@/components/ui";
import { copy } from "@/copy/ko";

export interface ApplyRow {
  label: string;
  before: string;
  after: string;
}

interface ApplyConfirmModalProps {
  open: boolean;
  rows: ApplyRow[];
  confirming: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

/** 적용 확인(FR-PLAN-01/02). 취소는 서버를 부르지 않는다. 대안·비교·대화 화면이 같이 쓴다 */
export function ApplyConfirmModal({ open, rows, confirming, onCancel, onConfirm }: ApplyConfirmModalProps) {
  return (
    <Modal
      open={open}
      title={copy.plan.applyTitle}
      onClose={onCancel}
      actions={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={confirming}>
            {copy.plan.cancel}
          </Button>
          <Button onClick={onConfirm} loading={confirming}>
            {copy.plan.confirm}
          </Button>
        </>
      }
    >
      <dl className="flex flex-col gap-2">
        {rows.map((row) => (
          <div key={row.label} className="flex justify-between gap-4">
            <dt className="text-text-muted">{row.label}</dt>
            <dd className="font-semibold">
              {row.before} → {row.after}
            </dd>
          </div>
        ))}
      </dl>
      <p className="mt-4 text-xs text-text-muted">{copy.plan.applyNote}</p>
    </Modal>
  );
}
