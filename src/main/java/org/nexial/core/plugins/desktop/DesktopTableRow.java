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

package org.nexial.core.plugins.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.openqa.selenium.WebElement;

public class DesktopTableRow {
    private int row;
    private boolean newRow;
    private Map<String, WebElement> columns;
    private DesktopTable table;
    private String editableColumnName;

    public static class TableRowMetadata {
        private int row;
        private boolean newRow;
        private List<String> columnNames;

        public int getRow() { return row; }

        public void setRow(int row) { this.row = row; }

        public boolean isNewRow() { return newRow; }

        public void setNewRow(boolean newRow) { this.newRow = newRow; }

        public List<String> getColumnNames() { return columnNames; }

        public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }

        @Override
        public String toString() {
            return new ToStringBuilder(this).append("row", row)
                                            .append("newRow", newRow)
                                            .append("columnNames", columnNames)
                                            .toString();
        }
    }

    public String getEditableColumnName() { return editableColumnName; }

    public void setEditableColumnName(String editableColumnName) { this.editableColumnName = editableColumnName; }

    public int getRow() { return row;}

    public void setRow(int row) { this.row = row;}

    public boolean isNewRow() { return newRow;}

    public void setNewRow(boolean newRow) { this.newRow = newRow;}

    public Map<String, WebElement> getColumns() { return columns;}

    public void setColumns(Map<String, WebElement> columns) { this.columns = columns;}

    public DesktopTable getTable() { return table;}

    public void setTable(DesktopTable table) { this.table = table;}

    public TableRowMetadata toMetadata() {
        TableRowMetadata metadata = new TableRowMetadata();
        metadata.setRow(row);
        metadata.setNewRow(newRow);
        if (MapUtils.isNotEmpty(columns)) { metadata.setColumnNames(new ArrayList<>(columns.keySet())); }
        return metadata;
    }
}
