/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.variable;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.nexial.commons.utils.SocialDateText;
import org.nexial.core.utils.ConsoleUtils;

import static java.util.Calendar.*;
import static org.nexial.core.NexialConst.*;

public class Date {

    enum DatePrecision {
        YEAR(1000L * 60 * 60 * 24 * 365),
        MONTH(1000L * 60 * 60 * 24 * 30),
        WEEK(1000 * 60 * 60 * 24 * 7),
        DAY(1000 * 60 * 60 * 24),
        HOUR(1000 * 60 * 60),
        MINUTE(1000 * 60),
        SECOND(1000),
        MILLISECOND(1);

        private double timestampDivider;

        DatePrecision(double timestampDivider) { this.timestampDivider = timestampDivider; }

        public String format(long timestamp) {
            double diff = Math.abs(timestamp) / timestampDivider;
            return diff == (long) diff ? ((long) diff) + "" : diff + "";
        }
    }

    private abstract class DateTransform {
        abstract Calendar transform(Calendar c);
    }

    public String stdFormat(String date, String fromFormat) { return format(date, fromFormat, STD_DATE_FORMAT); }

    public String format(String date, String fromFormat, String toFormat) {
        java.util.Date dateObject = parseDate(date, fromFormat);
        if (dateObject == null) { return date; }
        return StringUtils.defaultIfEmpty(fromDate(dateObject, toFormat, fromFormat), date);
    }

    public String addYear(String date, String years) { return addYear(date, NumberUtils.toInt(years)); }

    public String addYear(String date, int years) { return modifyDate(date, YEAR, years); }

    public String addMonth(String date, String months) { return addMonth(date, NumberUtils.toInt(months)); }

    public String addMonth(String date, int months) { return modifyDate(date, MONTH, months); }

    public String addDay(String date, String days) { return addDay(date, NumberUtils.toInt(days)); }

    public String addDay(String date, int days) { return modifyDate(date, DAY_OF_MONTH, days); }

    public String addHour(String date, String hours) { return addHour(date, NumberUtils.toInt(hours)); }

    public String addHour(String date, int hours) { return modifyDate(date, HOUR_OF_DAY, hours); }

    public String addMinute(String date, String minutes) { return addMinute(date, NumberUtils.toInt(minutes));}

    public String addMinute(String date, int minutes) { return modifyDate(date, MINUTE, minutes); }

    public String addSecond(String date, String seconds) { return addSecond(date, NumberUtils.toInt(seconds));}

    public String addSecond(String date, int seconds) { return modifyDate(date, SECOND, seconds); }

    public String setYear(String date, String years) { return setYear(date, NumberUtils.toInt(years)); }

    public String setYear(String date, int years) { return setDate(date, YEAR, years); }

    public String setMonth(String date, String months) { return setMonth(date, NumberUtils.toInt(months)); }

    public String setMonth(String date, int months) { return setDate(date, MONTH, months - 1); }

    public String setDay(String date, String days) { return setDay(date, NumberUtils.toInt(days)); }

    public String setDay(String date, int days) { return setDate(date, DAY_OF_MONTH, days); }

    public String setDOW(String date, String day) { return setDOW(date, NumberUtils.toInt(day)); }

    public String setDOW(String date, int days) { return setDate(date, DAY_OF_WEEK, days + 1); }

    public String setHour(String date, String hours) { return setHour(date, NumberUtils.toInt(hours)); }

    public String setHour(String date, int hours) { return setDate(date, HOUR_OF_DAY, hours); }

    public String setMinute(String date, String minutes) { return setMinute(date, NumberUtils.toInt(minutes)); }

    public String setMinute(String date, int minutes) { return setDate(date, MINUTE, minutes); }

    public String setSecond(String date, String seconds) { return setSecond(date, NumberUtils.toInt(seconds)); }

    public String setSecond(String date, int seconds) { return setDate(date, SECOND, seconds); }

    /**
     * assumed that {@code date1} and {@code date2} are both in the format of <code>MM/dd/yyyy HH:mm:ss</code>.
     * {@code precision} should match to one of the predefined value in {@link DatePrecision}
     */
    public String diff(String date1, String date2, String precision) {
        java.util.Date d1 = parseDate(date1, STD_DATE_FORMAT);
        if (d1 == null) {
            ConsoleUtils.error("Unable to parse date1 in the standard format " + STD_DATE_FORMAT + ": " + date1);
            return null;
        }

        java.util.Date d2 = parseDate(date2, STD_DATE_FORMAT);
        if (d2 == null) {
            ConsoleUtils.error("Unable to parse date2 in the standard format " + STD_DATE_FORMAT + ": " + date2);
            return null;
        }

        try {
            DatePrecision datePrecision = DatePrecision.valueOf(precision);
            return datePrecision.format(d1.getTime() - d2.getTime());
        } catch (IllegalArgumentException e) {
            ConsoleUtils.error("Unknown precision specified: " + precision);
            return null;
        }
    }

    protected java.util.Date parseDate(String date, String format) {
        if (StringUtils.isBlank(date)) { return null; }
        if (StringUtils.equals(format, EPOCH)) { return new java.util.Date(NumberUtils.toLong(date)); }
        if (StringUtils.equals(format, TIME_IN_TENS)) { return fromBase10Time(date); }

        // date format below
        try {
            return new SimpleDateFormat(format).parse(date);
        } catch (ParseException e) {
            ConsoleUtils.error("Unable to format date '" + date + "' as '" + format + "': " + e.getMessage());
            return null;
        }
    }

    protected String fromDate(java.util.Date date, String format, String sourceFormat) {
        if (date == null) { return null; }
        if (StringUtils.equals(format, EPOCH)) { return date.getTime() + ""; }
        if (StringUtils.equals(format, TIME_IN_TENS)) { return toBase10Time(date) + ""; }
        if (StringUtils.equals(format, HUMAN_DATE)) {
            return SocialDateText.since(date.getTime(), System.currentTimeMillis());
        }

        // toFormat is not EPOCH or HUMAN_DATE
        if (!StringUtils.equals(sourceFormat, EPOCH)) {
            // from a non-epoch date, that means standard date object/string
            return new SimpleDateFormat(format).format(date);
        } else {
            // else, from epoch to another date format

            // from epoch to a full date format (contains era, years or month)
            if (StringUtils.containsAny(format, "GYyML") && date.getTime() >= ONEYEAR) {
                return new SimpleDateFormat(format).format(date);
            }

            // epoch to another time or "day" format
            // make sure we don't have "bad" hours; duration must be in the 24-hour format
            format = StringUtils.replace(format, "h", "H");
            return DurationFormatUtils.formatDuration(date.getTime(), format);
        }
    }

    protected static java.util.Date fromBase10Time(String date) {
        if (StringUtils.isBlank(date)) { return null; }
        if (!NumberUtils.isParsable(date)) { return null; }

        double timeNumber = NumberUtils.toDouble(date);

        int hour = new Double(timeNumber).intValue();
        timeNumber = 60 * (timeNumber - hour);

        int minute = new Double(timeNumber).intValue();
        timeNumber = 60 * (timeNumber - minute);

        int second = new Double(timeNumber).intValue();
        int millisecond = (int) Math.round(1000 * (timeNumber - second));

        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(0);
        if (hour > 23) {
            c.set(DAY_OF_YEAR, hour / 24);
            c.set(HOUR_OF_DAY, hour % 24);
        } else {
            c.set(HOUR_OF_DAY, hour);
        }
        c.set(MINUTE, minute);
        c.set(SECOND, second);
        c.set(MILLISECOND, millisecond);
        return c.getTime();
    }

    protected static double toBase10Time(java.util.Date date) {
        if (date == null) { return -1; }

        Calendar c = new GregorianCalendar();
        c.setTime(date);

        // `1970` is consider the `0` year
        // Day `1` should not be counted towards `DAY_OF_YEAR`
        double base10 = (c.get(YEAR) - 1970) * 365 * 24 +
                        (c.get(DAY_OF_YEAR) - 1) * 24 +
                        c.get(HOUR_OF_DAY) +
                        (double) c.get(MINUTE) / 60 +
                        (double) c.get(SECOND) / 60 / 60 +
                        (double) c.get(MILLISECOND) / 60 / 60 / 1000;

        DecimalFormat df = new DecimalFormat();
        df.setGroupingUsed(false);
        df.setMaximumFractionDigits(10);

        try {
            return df.parse(df.format(base10)).doubleValue();
        } catch (Exception e) {
            throw new RuntimeException("Unable to parse '" + base10 + "' as base10 time value");
        }
    }

    protected void init() { }

    private String setDate(String date, final int dateField, int variant) {
        switch (dateField) {
            case YEAR: {
                if (variant < 1970 || variant > 9999) {
                    throw new IllegalArgumentException("Invalid year: " + variant + ". Consider a year between " +
                                                       "1970 and 9999.");
                }
                break;
            }
            // case MONTH: {
            //     if (variant < 1 || variant > 12) {
            //         throw new IllegalArgumentException("Invalid month: " + variant + ". Consider a number between " +
            //                                            "1 and 12.");
            //     }
            //     break;
            // }
            case DAY_OF_WEEK: {
                // in Nexial we treat 0 as Sunday, but in java.util.Calendar Sunday is 1
                // so we need to adjust accordingly
                if (variant < 1 || variant > 8) {
                    throw new IllegalArgumentException("Invalid day of the week: " + variant + ". " +
                                                       "Consider a number from 0 to 7.");
                }
                if (variant == 8) { variant = 1; }
                break;
            }
            // case DAY_OF_MONTH: {
            //     if (variant < 1 || variant > 31) {
            //         throw new IllegalArgumentException("Invalid day of the month: " + variant + ". " +
            //                                            "Consider a value between 1 and 31.");
            //     }
            //     break;
            // }
            // case HOUR_OF_DAY: {
            //     if (variant < 0 || variant > 24) {
            //         throw new IllegalArgumentException("Invalid hour of the day: " + variant + ". " +
            //                                            "Consider a value between 0 and 24.");
            //     }
            //     if (variant == 24) { variant = 0; }
            //     break;
            // }
            // case MINUTE:
            // case SECOND: {
            //     if (variant < 0 || variant > 60) {
            //         throw new IllegalArgumentException("Invalid minute: " + variant + ". " +
            //                                            "Consider a value between 0 and 60.");
            //     }
            //     // if (variant == 60) { variant = 0; }
            //     break;
            // }
        }

        final int dateValue = variant;
        return transformDate(date, new DateTransform() {
            Calendar transform(Calendar c) {
                c.set(dateField, dateValue);
                return c;
            }
        });
    }

    private String modifyDate(String date, final int dateField, final int variant) {
        return transformDate(date, new DateTransform() {
            Calendar transform(Calendar c) {
                c.add(dateField, variant);
                return c;
            }
        });
    }

    private String transformDate(String date, DateTransform dateTransform) {
        if (StringUtils.isBlank(date)) { return date; }

        String datePattern = StringUtils.length(date) < 11 ? STD_JUST_DATE_FORMAT : STD_DATE_FORMAT;

        try {
            DateFormat dateFormat = new SimpleDateFormat(datePattern);
            Calendar c = getInstance();
            c.setTime(dateFormat.parse(date));
            c = dateTransform.transform(c);
            return dateFormat.format(c.getTime());
        } catch (ParseException e) {
            ConsoleUtils.error("Unable to parse date '" + date + "' via format '" + datePattern + "'");
            return date;
        }
    }
}