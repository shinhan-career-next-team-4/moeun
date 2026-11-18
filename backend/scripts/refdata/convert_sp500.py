"""S&P500 원본 CSV를 서버 시드 형식(ym,close_value)으로 바꾼다.

S&P500 지수 데이터는 이용 조건(TBD-08) 때문에 레포에 올리지 않는다. 각자 원본을 받아 이 스크립트로 바꾼 뒤
backend/data/seed/sp500-monthly.csv에 두면 서버가 뜰 때 적재한다. 받는 방법은 src/main/resources/seed/README.md.

지원하는 원본
- Investing.com 한국어 월별·일별: "날짜","종가",... (날짜 "2016- 10- 01", 숫자 "2,126.20", 최근 날짜부터)
- Stooq 일별·월별: Date,Open,High,Low,Close,Volume
- FRED 일별: observation_date,SP500 (빈 값은 "." 또는 빈칸)
어느 원본이든 달마다 가장 늦은 날짜의 종가를 그 달 값으로 쓴다(월별 파일은 한 달에 한 줄이라 그 줄이 쓰인다).

사용: python3 backend/scripts/refdata/convert_sp500.py --input "$HOME/Downloads/S&P 500 과거 데이터.csv"
"""
import argparse
import csv
import datetime
import os
import sys
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

DEFAULT_END_MONTH = "2026-10"
# 관측 기간 120개월의 한 달 수익률을 만들려면 종가가 121개 필요하다(calc-spec 7-1)
DEFAULT_MONTHS = 121
DEFAULT_OUTPUT = os.path.join(os.path.dirname(__file__), "..", "..", "data", "seed", "sp500-monthly.csv")
MAX_DECIMAL_PLACES = Decimal("0.0001")  # sp500_monthly.close_value NUMERIC(14,4)


class ConversionError(Exception):
    """원본 파일로 시드를 만들 수 없을 때."""


def detect_columns(header):
    """원본 종류와 (날짜 열, 종가 열) 위치를 찾는다."""
    names = [name.strip() for name in header]
    if "날짜" in names and "종가" in names:
        return "Investing.com", names.index("날짜"), names.index("종가")
    if "Date" in names and "Close" in names:
        return "Stooq", names.index("Date"), names.index("Close")
    for date_name in ("observation_date", "DATE"):
        if date_name in names and "SP500" in names:
            return "FRED", names.index(date_name), names.index("SP500")
    raise ConversionError("unknown header: " + ",".join(names))


def read_month_end_closes(path):
    """달마다 가장 늦은 날짜의 종가를 고른다."""
    with open(path, encoding="utf-8-sig", newline="") as file:
        rows = list(csv.reader(file))
    if not rows:
        raise ConversionError("empty file")
    source, date_index, close_index = detect_columns(rows[0])
    latest = {}
    for row in rows[1:]:
        if len(row) <= max(date_index, close_index):
            continue
        text = row[close_index].strip()
        if text in ("", "."):
            continue
        try:
            # Investing.com은 날짜를 "2016- 10- 01", 숫자를 "2,126.20"처럼 쓴다
            day = datetime.date.fromisoformat(row[date_index].replace(" ", ""))
            close = Decimal(text.replace(",", ""))
        except (ValueError, InvalidOperation):
            raise ConversionError("cannot read row: " + ",".join(row))
        month = day.strftime("%Y-%m")
        if month not in latest or day > latest[month][0]:
            latest[month] = (day, close)
    return source, {month: close for month, (_, close) in latest.items()}


def list_months(end_month, count):
    year, month = map(int, end_month.split("-"))
    months = []
    for _ in range(count):
        months.append(f"{year:04d}-{month:02d}")
        month -= 1
        if month == 0:
            year, month = year - 1, 12
    return list(reversed(months))


def format_close(close):
    if close.as_tuple().exponent < -4:
        close = close.quantize(MAX_DECIMAL_PLACES, rounding=ROUND_HALF_UP)
    return format(close.normalize(), "f")


def convert(input_path, output_path, end_month, months, today):
    # 진행 중인 달의 값은 월말 종가가 아니라서 받지 않는다
    if end_month >= today.strftime("%Y-%m"):
        raise ConversionError(end_month + " is not finished yet")
    source, closes = read_month_end_closes(input_path)
    wanted = list_months(end_month, months)
    missing = [month for month in wanted if month not in closes]
    if missing:
        raise ConversionError("missing months: " + ", ".join(missing))
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as file:
        file.write("# S&P500 지수 월말 종가(달러, 가격 지수). 이용 조건 때문에 레포에 올리지 않는다(TBD-08).\n")
        file.write(f"# 원본: {source}, 변환일: {today.isoformat()}, 기간: {wanted[0]} ~ {wanted[-1]}\n")
        file.write("ym,close_value\n")
        for month in wanted:
            file.write(f"{month},{format_close(closes[month])}\n")


def main():
    parser = argparse.ArgumentParser(description="S&P500 원본 CSV → 시드 CSV(ym,close_value)")
    parser.add_argument("--input", required=True, help="받은 원본 CSV 경로(Investing.com, Stooq, FRED)")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="결과 경로(기본: backend/data/seed/sp500-monthly.csv)")
    parser.add_argument("--end-month", default=DEFAULT_END_MONTH, help="마지막 달(yyyy-MM). 팀 모두 같은 값을 써야 같은 데이터가 된다")
    parser.add_argument("--months", type=int, default=DEFAULT_MONTHS, help="남길 개월 수")
    args = parser.parse_args()
    try:
        convert(args.input, args.output, args.end_month, args.months, datetime.date.today())
    except ConversionError as error:
        print("변환 실패: " + str(error), file=sys.stderr)
        return 1
    print("저장: " + os.path.abspath(args.output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
