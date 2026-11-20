"""convert_etf_monthly.py 테스트. 실행: python3 -m unittest discover -s backend/scripts/refdata"""
import datetime
import os
import tempfile
import unittest

import convert_etf_monthly as converter

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


class ConvertEtfMonthlyTest(unittest.TestCase):

    def convert(self, text, end_month="2026-10", months=121, product="SP500"):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "source.csv")
            target = os.path.join(folder, "out", "monthly.csv")
            with open(source, "w", encoding="utf-8") as file:
                file.write(text)
            converter.convert(source, target, end_month, months, TODAY, product)
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

    def test_reads_investing_korean_monthly_file(self):
        # Investing.com 한국어 월별: BOM, 따옴표, "2016- 10- 01" 날짜, 쉼표 숫자, 최근 달부터 내림차순
        lines = ['﻿"날짜","종가","시가","고가","저가","거래량","변동 %"']
        # 진행 중인 달(2026-11)도 파일에 들어 있지만 end_month까지만 남아야 한다
        lines.append('"2026- 11- 01","7,340.89","7,610.83","7,715.57","7,288.67","","-3.16%"')
        year, month = 2026, 10
        for index in range(121):
            lines.append(f'"{year:04d}- {month:02d}- 01","{index + 1},000.50","1","1","1","","0.00%"')
            month -= 1
            if month == 0:
                year, month = year - 1, 12
        data = self.convert("\n".join(lines) + "\n")
        rows = [line for line in data if not line.startswith("#")]
        self.assertEqual(len(rows) - 1, 121)
        self.assertEqual(rows[1], "2016-10,121000.5")
        self.assertEqual(rows[-1], "2026-10,1000.5")
        self.assertTrue(any("Investing" in line for line in data if line.startswith("#")))

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


    def test_reads_investing_file_in_oldest_first_order_and_drops_unfinished_month(self):
        # 069500·133690 파일은 오래된 날짜부터이고, 진행 중인 달(2026-11)이 들어 있다
        lines = ['"날짜","종가","시가","고가","저가","거래량","변동 %"']
        year, month = 2016, 10
        for index in range(122):
            lines.append(f'"{year:04d}- {month:02d}- 01","{25000 + index:,}","1","1","1","1M","0.1%"')
            month += 1
            if month == 13:
                year, month = year + 1, 1
        data = [line for line in self.convert("\n".join(lines) + "\n", product="069500")
                if not line.startswith("#")]
        self.assertEqual(data[0], "ym,close_value")
        self.assertEqual(data[1], "2016-10,25000")
        self.assertEqual(data[-1], "2026-10,25120")
        self.assertEqual(len(data), 122)

    def test_writes_product_code_in_comment(self):
        lines = self.convert(stooq_csv(2016, 10, 121), product="133690")
        self.assertIn("133690", lines[0])

    def test_default_output_follows_product_code(self):
        self.assertTrue(converter.default_output("SP500").endswith(os.path.join("data", "seed", "sp500-monthly.csv")))
        self.assertTrue(converter.default_output("069500").endswith(
            os.path.join("data", "seed", "etf-069500-monthly.csv")))


if __name__ == "__main__":
    unittest.main()
