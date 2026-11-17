"""convert_sp500.py 테스트. 실행: python3 -m unittest discover -s backend/scripts/refdata"""
import datetime
import os
import tempfile
import unittest

import convert_sp500 as converter

TODAY = datetime.date(2026, 11, 17)


def stooq_csv(start_year, start_month, count, skip=None):
    """Stooq 월별 CSV 모양(Date,Open,High,Low,Close,Volume). 날짜는 그 달의 마지막 거래일처럼 28일로 둔다."""
    lines = ["Date,Open,High,Low,Close,Volume"]
    year, month = start_year, start_month
    for index in range(count):
        ym = f"{year:04d}-{month:02d}"
        if ym != skip:
            close = 2000 + index
            lines.append(f"{ym}-28,{close},{close},{close},{close}.25,1000")
        month += 1
        if month == 13:
            year, month = year + 1, 1
    return "\n".join(lines) + "\n"


class ConvertSp500Test(unittest.TestCase):

    def convert(self, text, end_month="2026-10", months=121):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "source.csv")
            target = os.path.join(folder, "out", "sp500-monthly.csv")
            with open(source, "w", encoding="utf-8") as file:
                file.write(text)
            converter.convert(source, target, end_month, months, TODAY)
            with open(target, encoding="utf-8") as file:
                return file.read().splitlines()

    def test_keeps_last_121_months_ending_at_end_month(self):
        lines = self.convert(stooq_csv(2015, 1, 142))
        data = [line for line in lines if not line.startswith("#")]
        self.assertEqual(data[0], "ym,close_value")
        self.assertEqual(len(data) - 1, 121)
        self.assertTrue(data[1].startswith("2016-10,"))
        self.assertTrue(data[-1].startswith("2026-10,"))

    def test_writes_source_and_not_for_repo_comment(self):
        lines = self.convert(stooq_csv(2016, 10, 121))
        self.assertTrue(any("Stooq" in line for line in lines if line.startswith("#")))
        self.assertTrue(any("레포에 올리지 않는다" in line for line in lines if line.startswith("#")))

    def test_uses_last_trading_day_of_each_month_for_fred_daily_data(self):
        lines = ["observation_date,SP500"]
        day = datetime.date(2016, 10, 1)
        while day <= datetime.date(2026, 10, 31):
            value = "." if day.day == 31 else f"{day.year}{day.month:02d}.{day.day:02d}"
            lines.append(f"{day.isoformat()},{value}")
            day += datetime.timedelta(days=1)
        data = [line for line in self.convert("\n".join(lines) + "\n") if not line.startswith("#")]
        # 31일 값은 비어 있으므로(".") 그 달의 마지막 값은 30일 값이다
        self.assertEqual(data[1], "2016-10,201610.3")
        self.assertEqual(data[-1], "2026-10,202610.3")

    def test_rejects_missing_month(self):
        with self.assertRaisesRegex(converter.ConversionError, "missing months: 2020-03"):
            self.convert(stooq_csv(2016, 10, 121, skip="2020-03"))

    def test_rejects_not_enough_history(self):
        with self.assertRaisesRegex(converter.ConversionError, "missing months: 2016-10"):
            self.convert(stooq_csv(2016, 11, 120))

    def test_rejects_end_month_that_is_not_finished(self):
        with self.assertRaisesRegex(converter.ConversionError, "not finished"):
            self.convert(stooq_csv(2016, 11, 121), end_month="2026-11")

    def test_rejects_unknown_file_format(self):
        with self.assertRaisesRegex(converter.ConversionError, "unknown header"):
            self.convert("Datum,Wert\n2026-10-30,1\n")


if __name__ == "__main__":
    unittest.main()
