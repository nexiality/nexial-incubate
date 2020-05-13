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

import java.util.Arrays;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.ExecutionThread;
import org.nexial.core.model.ExecutionContext;

import static org.nexial.core.NexialConst.Data.TEXT_DELIM;
import static org.nexial.core.SystemVariables.getDefault;

public class Array {

    public String item(String array, String index) {
        return StringUtils.isEmpty(array) ? "" : item(toArray(array), index);
    }

    public String length(String array) {
        return StringUtils.isEmpty(array) ? "0" : toArray(array).length + "";
    }

    public String reverse(String array) {
        if (StringUtils.isEmpty(array)) { return array; }

        String[] arr = toArray(array);
        ArrayUtils.reverse(arr);
        return toString(Arrays.stream(arr));
    }

    public String subarray(String array, String start, String end) {
        return StringUtils.isEmpty(array) ? "" : toString(subarray(toArray(array), start, end));
    }

    public String distinct(String array) {
        return StringUtils.isEmpty(array) ? "" : toString(Arrays.stream(toArray(array)).distinct());
    }

    public String ascending(String array) {
        return StringUtils.isEmpty(array) ? "" : toString(sort(toArray(array), true));
    }

    public String descending(String array) {
        return StringUtils.isEmpty(array) ? "" : toString(sort(toArray(array), false));
    }

    public static Stream<String> sort(String[] array, boolean ascending) {
        return Arrays.stream(array).sorted((o1, o2) -> ascending ? compare(o1, o2) : compare(o2, o1));
    }

    public String remove(String array, String index) {
        if (StringUtils.isEmpty(array)) { return ""; }
        if (!NumberUtils.isDigits(index)) { return ""; }

        int idx = NumberUtils.toInt(index);
        if (idx < 0) { return ""; }

        String[] arr = toArray(array);
        if (arr.length <= idx) { return ""; }

        String delim = getDelim();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i == idx) { continue; }
            buffer.append(arr[i]).append(delim);
        }

        return StringUtils.removeEnd(buffer.toString(), delim);
    }

    public String insert(String array, String index, String item) {
        if (StringUtils.isEmpty(array)) { return ""; }
        if (StringUtils.isEmpty(item)) { return ""; }
        if (!NumberUtils.isDigits(index)) { return ""; }

        int idx = NumberUtils.toInt(index);
        if (idx < 0) { return ""; }

        String[] arr = toArray(array);
        if (arr.length <= idx) { return ""; }

        String delim = getDelim();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i == idx) { buffer.append(item).append(delim); }
            buffer.append(arr[i]).append(delim);
        }

        return StringUtils.removeEnd(buffer.toString(), delim);
    }

    public String prepend(String array, String item) {
        if (StringUtils.isEmpty(array)) { return ""; }
        if (StringUtils.isEmpty(item)) { return ""; }
        return item + getDelim() + array;
    }

    public String append(String array, String item) {
        if (StringUtils.isEmpty(array)) { return ""; }
        if (StringUtils.isEmpty(item)) { return ""; }
        String delim = getDelim();
        return StringUtils.removeEnd(array, delim) + delim + item;
    }

    public String index(String array, String item) {
        if (StringUtils.isEmpty(array)) { return ""; }
        if (StringUtils.isEmpty(item)) { return ""; }

        String[] arr = toArray(array);
        for (int i = 0; i < arr.length; i++) {
            if (StringUtils.equals(arr[i], item)) { return i + ""; }
        }

        return "";
    }

    /** remove empty or null items from {@code array} */
    public String pack(String array) {
        if (StringUtils.isEmpty(array)) { return ""; }

        String[] arr = pack(toArray(array));
        if (ArrayUtils.isEmpty(arr)) { return ""; }
        return toString(Arrays.stream(arr));
    }

    public String replica(String array, String count) {
        if (StringUtils.isEmpty(array)) { return ""; }
        String[] replica = replica(toArray(array), count);
        if (ArrayUtils.isEmpty(replica)) { return ""; }
        return toString(Arrays.stream(replica));
    }

    public String replicaUntil(String array, String size) {
        if (StringUtils.isEmpty(array)) { return ""; }
        String[] replica = replicaUntil(toArray(array), size);
        if (ArrayUtils.isEmpty(replica)) { return ""; }
        return toString(Arrays.stream(replica));
    }

    public static String toString(String... strings) {
        return strings == null ? null : toString(Arrays.stream(strings));
    }

    protected static int compare(String value1, String value2) {
        if (NumberUtils.isParsable(value1) && NumberUtils.isParsable(value2)) {
            return NumberUtils.createBigDecimal(value1).compareTo(NumberUtils.createBigDecimal(value2));
        } else {
            return value1.compareTo(value2);
        }
    }

    protected static String item(String[] arr, String index) {
        return !NumberUtils.isDigits(index) ? "" : item(arr, NumberUtils.toInt(index));
    }

    protected static String item(String[] arr, int idx) {
        if (ArrayUtils.isEmpty(arr)) { return ""; }
        if (idx < 0) { return ""; }
        if (arr.length <= idx) { return ""; }

        return arr[idx];
    }

    /** subarray between start and end, both ends inclusively */
    protected static String[] subarray(String[] arr, String start, String end) {
        if (ArrayUtils.isEmpty(arr)) { return null; }

        if (!NumberUtils.isDigits(start)) { return null; }
        int idxStart = NumberUtils.toInt(start);
        if (idxStart < 0) { return null; }

        int idxEnd;
        if (StringUtils.isEmpty(end) || end.equals("-1")) {
            idxEnd = arr.length - 1;
        } else if (!NumberUtils.isCreatable(end)) {
            return null;
        } else {
            idxEnd = NumberUtils.toInt(end);
        }
        if (idxEnd >= arr.length) { return arr; }
        if (idxEnd == -1) {
            idxEnd = arr.length - 1;
        } else if (idxEnd <= idxStart) {
            return null;
        }

        return ArrayUtils.subarray(arr, idxStart, idxEnd + 1);
    }

    protected static String[] pack(String[] arr) {
        if (ArrayUtils.isEmpty(arr)) { return null; }
        return ArrayUtils.removeElements(ArrayUtils.removeAllOccurences(arr, ""), (String) null);
    }

    protected static String[] replica(String[] arr, String count) {
        if (ArrayUtils.isEmpty(arr)) { return null; }

        if (!NumberUtils.isDigits(count)) { return arr; }
        int counter = NumberUtils.toInt(count);
        if (counter < 0) { return arr; }

        String[] newArr = new String[]{};
        for (int i = 0; i < counter; i++) {
            newArr = ArrayUtils.addAll(newArr, arr);
        }
        return newArr;
    }

    protected static String[] replicaUntil(String[] arr, String size) {
        if (ArrayUtils.isEmpty(arr)) { return null; }

        if (!NumberUtils.isDigits(size)) { return arr; }

        int maxSize = NumberUtils.toInt(size);
        if (maxSize < 0) { return arr; }

        if (maxSize > arr.length) {
            int maxLoopCount = (maxSize / arr.length) + 1;
            String[] newArr = new String[]{};
            for (int i = 0; i < maxLoopCount; i++) { newArr = ArrayUtils.addAll(newArr, arr); }
            arr = newArr;
        }

        return ArrayUtils.subarray(arr, 0, maxSize);
    }

    protected static String toString(Stream<String> stream) {
        String delim = getDelim();
        StringBuilder buffer = new StringBuilder();
        stream.forEach(item -> buffer.append(StringUtils.replace(item, delim, "\\" + delim)).append(delim));
        return StringUtils.removeEnd(buffer.toString(), delim);
    }

    protected static String[] toArray(String array) { return toArray(array, getDelim()); }

    protected static String[] toArray(String array, String delim) {
        if (!StringUtils.contains(array, delim)) { return new String[]{array}; }

        // need special parsing to compensate the case of web elements which looks like [[...]],[[...]],..
        if (TextUtils.isBetween(array, "[", "]")) {
            if (StringUtils.startsWith(array, "[[") && StringUtils.contains(array, "],[[")) {
                // likely web element lists.. need special care
                String[] split = StringUtils.splitByWholeSeparator(array, "],[");
                return Arrays.stream(split)
                             .map(str -> (StringUtils.startsWith(str, "[[") ? "" : "[") +
                                         StringUtils.appendIfMissing(str, "]"))
                             .toArray(String[]::new);
            }

            array = TextUtils.substringBetweenFirstPair(array, "[", "]");
        }

        if (TextUtils.isBetween(array, "{", "}")) {
            array = TextUtils.substringBetweenFirstPair(array, "{", "}");
        }

        return StringUtils.splitByWholeSeparatorPreserveAllTokens(array, delim);
    }

    protected static String getDelim() {
        ExecutionContext context = ExecutionThread.get();
        return context == null ? getDefault(TEXT_DELIM) : context.getTextDelim();
    }

    protected void init() { }
}
