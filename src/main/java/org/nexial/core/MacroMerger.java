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

package org.nexial.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;

import org.nexial.commons.utils.FileUtil;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.excel.ExcelAddress;
import org.nexial.core.excel.ExcelArea;
import org.nexial.core.model.TestProject;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.InputFileUtils;

import static org.nexial.core.excel.ExcelConfig.*;
import static java.io.File.separator;
import static org.apache.poi.ss.usermodel.CellType.STRING;
import static org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK;

public class MacroMerger {
	private static final String TEST_STEPS_PREFIX =
		"" + COL_TEST_CASE + (ADDR_COMMAND_START.getRowStartIndex() + 1) + ":" + COL_REASON;
	private static final String TEST_COMMAND_MACRO = "base.macro(file,sheet,name)";

	private static final Map<String, List<List<String>>> MACRO_CACHE = new HashMap<>();
	private Excel excel;
	private TestProject project;

	protected void mergeMacro(Excel excel, TestProject project) throws IOException {
		this.excel = excel;
		this.project = project;

		excel.getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);

		// find all scenario sheets
		List<Worksheet> testSheets = InputFileUtils.retrieveValidTestScenarios(excel);
		assert testSheets != null;
		boolean fileModified = false;

		// scan all test steps
		for (Worksheet sheet : testSheets) {
			// collect all test steps (also clear existing test steps in sheet)
			List<List<String>> allTestSteps = harvestAllTestSteps(sheet);

			//ConsoleUtils.log("sheet " + sheet.getName() + " - original test steps:\n\t\t" + TextUtils.toString(allTestSteps, "\n\t\t"));

			// scan through all test steps
			if (expandTestSteps(allTestSteps)) {
				refillExpandedTestSteps(sheet, allTestSteps);
				fileModified = true;
			}
		}

		// save excel, rinse and repeat.
		if (fileModified) {
			ConsoleUtils.log("macro(s) merged, saving excel " + excel.getFile());
			excel.save();
		}
	}

	protected boolean expandTestSteps(List<List<String>> allTestSteps) throws IOException {
		boolean macroExpanded = false;

		for (int i = 0; i < allTestSteps.size(); i++) {
			List<String> row = allTestSteps.get(i);
			String activityName = row.get(COL_IDX_TESTCASE);
			String cellTarget = row.get(COL_IDX_TARGET);
			String cellCommand = row.get(COL_IDX_COMMAND);
			String testCommand = cellTarget + "." + cellCommand;

			// look for base.macro(file,sheet,name) - open macro library as excel
			if (StringUtils.equals(testCommand, TEST_COMMAND_MACRO)) {
				//ConsoleUtils.log(sheet.getName() + ": macro reference found - " + cellTarget + " - " + testCommand);

				String paramFile = row.get(COL_IDX_PARAMS_START);
				String paramSheet = row.get(COL_IDX_PARAMS_START + 1);
				String paramMacro = row.get(COL_IDX_PARAMS_START + 2);

				// use macro cache if possible
				// expand macro test steps, add them to cache
				List<List<String>> macroSteps = harvestMacroSteps(paramFile, paramSheet, paramMacro);
				if (CollectionUtils.isNotEmpty(macroSteps)) {
					//ConsoleUtils.log("found macro steps:\n\t\t" + TextUtils.toString(macroSteps, "\n\t\t"));

					// 9. replace macro invocation step with expanded macro test steps
					allTestSteps.remove(i);
					for (int j = 0; j < macroSteps.size(); j++) {
						List<String> macroStep = new ArrayList<>(macroSteps.get(j));
						macroStep.add(0, j == 0 ? activityName : "");
						allTestSteps.add(i + j, macroStep);
					}
					i += macroSteps.size();
					macroExpanded = true;
				}
			}
		}

		return macroExpanded;
	}

	protected void refillExpandedTestSteps(Worksheet sheet, List<List<String>> allTestSteps) {
		//ConsoleUtils.log(sheet.getName() + " - expanded test steps:\n\t\t" + TextUtils.toString(allTestSteps, "\n\t\t"));

		XSSFSheet excelSheet = sheet.getSheet();

		// remove existing rows
		int lastCommandRow = sheet.findLastDataRow(ADDR_COMMAND_START);
		ExcelArea area = new ExcelArea(sheet, new ExcelAddress(TEST_STEPS_PREFIX + lastCommandRow), false);
		List<List<XSSFCell>> testStepArea = area.getWholeArea();
		if (CollectionUtils.isNotEmpty(testStepArea)) {
			testStepArea.forEach(row -> excelSheet.removeRow(excelSheet.getRow(row.get(0).getRowIndex())));
		}

		// push expaneded test steps back to scenario sheet
		for (int i = 0; i < allTestSteps.size(); i++) {
			List<String> testStepRow = allTestSteps.get(i);
			int targetRowIdx = ADDR_COMMAND_START.getRowStartIndex() + i;
			XSSFRow excelRow = excelSheet.createRow(targetRowIdx);
			for (int j = 0; j < testStepRow.size(); j++) {
				String cellValue = testStepRow.get(j);
				excelRow.createCell(j, STRING).setCellValue(cellValue);
			}
		}
	}

	protected List<List<String>> harvestAllTestSteps(Worksheet sheet) {
		int lastCommandRow = sheet.findLastDataRow(ADDR_COMMAND_START);
		ExcelArea area = new ExcelArea(sheet, new ExcelAddress(TEST_STEPS_PREFIX + lastCommandRow), false);
		List<List<XSSFCell>> testStepArea = area.getWholeArea();
		List<List<String>> testStepData = new ArrayList<>();
		if (CollectionUtils.isEmpty(testStepArea)) { return testStepData; }

		//XSSFSheet excelSheet = sheet.getSheet();
		testStepArea.forEach(row -> {
			List<String> testStepRow = new ArrayList<>();
			row.forEach(cell -> testStepRow.add(Excel.getCellValue(cell)));
			//excelSheet.removeRow(excelSheet.getRow(row.get(0).getRowIndex()));
			testStepData.add(testStepRow);
		});

		return testStepData;
	}

	protected List<List<String>> harvestMacroSteps(String paramFile, String paramSheet, String paramMacro)
		throws IOException {

		// macro library can be specified as full path or relative path
		File macroFile;
		if (FileUtil.isFileReadable(paramFile, 5000)) {
			macroFile = new File(paramFile);
		} else {
			String macroFilePath = StringUtils.appendIfMissing(project.getScriptPath(), separator) + paramFile;
			macroFile = new File(macroFilePath);
		}

		String macroKey = macroFile + ":" + paramSheet + ":" + paramMacro;

		if (MACRO_CACHE.containsKey(macroKey)) {
			// shortcircut: in case the same macro is referenced
			ConsoleUtils.log("reading macro from cache: " + macroKey);
			return MACRO_CACHE.get(macroKey);
		}

		// open specified sheet
		Excel macroExcel = new Excel(macroFile, true);
		Worksheet macroSheet = macroExcel.worksheet(paramSheet);
		int lastMacroRow = macroSheet.findLastDataRow(ADDR_MACRO_COMMAND_START);
		ExcelArea macroArea = new ExcelArea(macroSheet, new ExcelAddress("A2:L" + lastMacroRow), false);
		List<List<XSSFCell>> macroStepArea = macroArea.getWholeArea();
		List<List<String>> macroSteps = new ArrayList<>();
		boolean macroFound = false;

		// 6. read test steps based on macro name
		for (List<XSSFCell> macroRow : macroStepArea) {
			String currentMacroName = macroRow.get(0).getStringCellValue();
			if (StringUtils.equals(currentMacroName, paramMacro)) {
				macroFound = true;
				macroSteps.add(collectMacroStep(macroRow));
				continue;
			}

			if (macroFound) {
				if (StringUtils.isBlank(currentMacroName)) {
					macroSteps.add(collectMacroStep(macroRow));
				} else {
					MACRO_CACHE.put(macroKey, macroSteps);
					macroSteps = new ArrayList<>();
					macroFound = false;
					break;
				}
			}
		}

		if (macroFound && !macroSteps.isEmpty()) { MACRO_CACHE.put(macroKey, macroSteps); }

		return MACRO_CACHE.get(macroKey);
	}

	protected List<String> collectMacroStep(List<XSSFCell> macroRow) {
		List<String> oneStep = new ArrayList<>();
		for (int i = 1; i <= 11; i++) { oneStep.add(macroRow.get(i).getStringCellValue()); }
		return oneStep;
	}
}
