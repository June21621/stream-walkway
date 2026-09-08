// 관측 지점 상태. DB 값은 active/inactive 두 가지이고, 그 외 값이 오면
// 임의로 해석하지 않고 원문을 그대로 보여준다.

const LABEL: Record<string, string> = {
  active: '운영 중',
  inactive: '중지',
};

export default function StatusChip({ status }: { status: string }) {
  const on = status === 'active';
  return (
    <span className={on ? 'chip chip--on' : 'chip'}>{LABEL[status] ?? status}</span>
  );
}
