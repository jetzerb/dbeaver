/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ui.controls.resultset.plaintext;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.IFindReplaceTarget;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextPrintOptions;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.themes.ITheme;
import org.eclipse.ui.themes.IThemeManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.impl.data.DBDValueError;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.ui.TextUtils;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.StyledTextFindReplaceTarget;
import org.jkiss.dbeaver.ui.controls.resultset.*;
import org.jkiss.utils.CommonUtils;

import java.text.DecimalFormatSymbols;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Empty presentation.
 * Used when RSV has no results (initially).
 */
public class PlainTextPresentation extends AbstractPresentation implements IAdaptable {

    public static final int FIRST_ROW_LINE = 2;

    private StyledText text;
    private DBDAttributeBinding curAttribute;
    private StyledTextFindReplaceTarget findReplaceTarget;
    public boolean activated;
    private Color curLineColor;

    private DBPPreferenceStore prefs;
    private boolean rightJustifyNumbers;
    private boolean rightJustifyDateTime;
    private boolean delimLeading;
    private boolean delimTrailing;
    private boolean showNulls;
    private int tabWidth;
    private int maxColumnSize;
    private String spaces;
    private DBDDisplayFormat displayFormat;
    private StringBuilder grid;
    private ResultSetModel model;
    private List<DBDAttributeBinding> attrs;
    private ColumnInformation[] colInfo;
    private StyleRange curLineRange;
    private int totalRows = 0;
    private String curSelection;
    private Font monoFont;

    @Override
    public void createPresentation(@NotNull final IResultSetController controller, @NotNull Composite parent) {
        super.createPresentation(controller, parent);

        UIUtils.createHorizontalLine(parent);
        text = new StyledText(parent, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setBlockSelection(true);
        text.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_IBEAM));
        text.setMargins(4, 4, 4, 4);
        text.setTabs(controller.getPreferenceStore().getInt(DBeaverPreferences.RESULT_TEXT_TAB_SIZE));
        text.setTabStops(null);
        text.setFont(JFaceResources.getFont(JFaceResources.TEXT_FONT));
        text.setLayoutData(new GridData(GridData.FILL_BOTH));
        text.addCaretListener(event -> onCursorChange(event.caretOffset));
        text.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                curSelection = text.getSelectionText();
                fireSelectionChanged(new PlainTextSelectionImpl());
            }
        });
        text.addDisposeListener(e -> dispose());

        final ScrollBar verticalBar = text.getVerticalBar();
        verticalBar.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (verticalBar.getSelection() + verticalBar.getPageIncrement() >= verticalBar.getMaximum()) {
                    if (controller.getPreferenceStore().getBoolean(DBeaverPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT) &&
                        !controller.isRecordMode() &&
                        controller.isHasMoreData()) {
                        controller.readNextSegment();
                    }
                }
            }
        });
        findReplaceTarget = new StyledTextFindReplaceTarget(text);
        UIUtils.enableHostEditorKeyBindingsSupport(controller.getSite(), text);

        applyThemeSettings();

        registerContextMenu();
        activateTextKeyBindings(controller, text);
        trackPresentationControl();
    }

    @Override
    public void dispose() {
        if (monoFont != null) {
            UIUtils.dispose(monoFont);
            monoFont = null;
        }
        super.dispose();
    }

    @Override
    protected void applyThemeSettings() {
        IThemeManager themeManager = controller.getSite().getWorkbenchWindow().getWorkbench().getThemeManager();
        curLineColor = themeManager.getCurrentTheme().getColorRegistry().get(ThemeConstants.COLOR_SQL_RESULT_CELL_ODD_BACK);

        ITheme currentTheme = themeManager.getCurrentTheme();
        Font rsFont = currentTheme.getFontRegistry().get(ThemeConstants.FONT_SQL_RESULT_SET);
        if (rsFont != null) {
            int fontHeight = rsFont.getFontData()[0].getHeight();
            Font font = JFaceResources.getFont(JFaceResources.TEXT_FONT);

            FontData[] fontData = font.getFontData();
            fontData[0].setHeight(fontHeight);
            Font newFont = new Font(font.getDevice(), fontData[0]);

            this.text.setFont(newFont);

            if (monoFont != null) {
                UIUtils.dispose(monoFont);
            }
            monoFont = newFont;

        }
    }

    private void onCursorChange(int offset) {
        model = controller.getModel();

        int lineNum = text.getLineAtOffset(offset);
        int lineOffset = text.getOffsetAtLine(lineNum);
        int horizontalOffset = offset - lineOffset;

        int lineCount = text.getLineCount();

        int rowNum = lineNum - FIRST_ROW_LINE; //First 2 lines is header
        if (controller.isRecordMode()) {
            if (rowNum < 0) {
                rowNum = 0;
            }
            if (rowNum >= 0 && rowNum < model.getVisibleAttributeCount()) {
                curAttribute = model.getVisibleAttribute(rowNum);
            }
        } else {
            int colNum = 0;
            int horOffsetBegin = 0, horOffsetEnd = 0;
            for (int i = 0; i < colInfo.length; i++) {
                horOffsetBegin = horOffsetEnd;
                horOffsetEnd += colInfo[i].length() + 1;
                if (horizontalOffset < horOffsetEnd) {
                    colNum = i;
                    break;
                }
            }
            if (rowNum < 0 && model.getRowCount() > 0) {
                rowNum = 0;
            }
            if (rowNum >= 0 && rowNum < model.getRowCount() && colNum >= 0 && colNum < model.getVisibleAttributeCount()) {
                controller.setCurrentRow(model.getRow(rowNum));
                curAttribute = model.getVisibleAttribute(colNum);
            }
            controller.updateEditControls();

            {
                // Highlight row
                if (curLineRange == null || curLineRange.start != lineOffset + horOffsetBegin) {
                    curLineRange = new StyleRange(
                        lineOffset + horOffsetBegin,
                        horOffsetEnd - horOffsetBegin - 1,
                        null,
                        curLineColor);
                    UIUtils.asyncExec(() -> {
                        text.setStyleRanges(new StyleRange[]{curLineRange});
                        text.redraw();
                    });
                }
            }

            if (lineNum == lineCount - 1 &&
                controller.isHasMoreData() &&
                controller.getPreferenceStore().getBoolean(DBeaverPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT)) {
                controller.readNextSegment();
            }
        }
        fireSelectionChanged(new PlainTextSelectionImpl());
    }

    @Override
    public Control getControl() {
        return text;
    }

    @Override
    public void refreshData(boolean refreshMetadata, boolean append, boolean keepState) {
        if (prefs == null || refreshMetadata) {
            prefs = getController().getPreferenceStore();
            rightJustifyNumbers = prefs.getBoolean(DBeaverPreferences.RESULT_SET_RIGHT_JUSTIFY_NUMBERS);
            rightJustifyDateTime = prefs.getBoolean(DBeaverPreferences.RESULT_SET_RIGHT_JUSTIFY_DATETIME);
            delimLeading = prefs.getBoolean(DBeaverPreferences.RESULT_TEXT_DELIMITER_LEADING);
            delimTrailing = prefs.getBoolean(DBeaverPreferences.RESULT_TEXT_DELIMITER_TRAILING);
            showNulls = prefs.getBoolean(DBeaverPreferences.RESULT_TEXT_SHOW_NULLS);
            displayFormat = DBDDisplayFormat.safeValueOf(prefs.getString(DBeaverPreferences.RESULT_TEXT_VALUE_FORMAT));
            tabWidth = prefs.getInt(DBeaverPreferences.RESULT_TEXT_TAB_SIZE);
            maxColumnSize = prefs.getInt(DBeaverPreferences.RESULT_TEXT_MAX_COLUMN_SIZE);
            spaces = new String(new char[maxColumnSize]).replace('\0', ' ');
            grid = new StringBuilder(512);
            model = controller.getModel();
            attrs = model.getVisibleAttributes();

            // Calculate column attributes
            colInfo = new ColumnInformation[attrs.size()];
            for (int i = 0; i < attrs.size(); i++) {
                DBDAttributeBinding attr = attrs.get(i);
                colInfo[i] = new ColumnInformation();
                colInfo[i].name = getAttributeName(attr);
                colInfo[i].strLen = colInfo[i].name.length();
                colInfo[i].useNumLen = (attr.getDataKind() == DBPDataKind.NUMERIC) && rightJustifyNumbers;
                colInfo[i].rightJustify = colInfo[i].useNumLen || (attr.getDataKind() == DBPDataKind.DATETIME) && rightJustifyDateTime;
                if (showNulls && !attr.isRequired()) {
                    colInfo[i].strLen = Math.max(colInfo[i].strLen, DBConstants.NULL_VALUE_LABEL.length());
                }
            }
        }

        if (controller.isRecordMode()) {
            printRecord();
        } else {
            printGrid(append);
        }
    }

    private void printGrid(boolean append) {
        List<ResultSetRow> allRows = model.getAllRows();
        ColumnInformation lenInfo;

        // make initial pass through the data to determine column widths,
        // and print headers
        if (!append) {
            for (ResultSetRow row : allRows) {
                for (int i = 0; i < attrs.size(); i++) {
                    DBDAttributeBinding attr = attrs.get(i);
                    String displayString = getCellString(model, attr, row, displayFormat);
                    lenInfo = getColInfo(displayString,colInfo[i].useNumLen,decimalSeparator);
                    if (colInfo[i].useNumLen) {
                        colInfo[i].intLen = Math.max(colInfo[i].intLen, lenInfo.intLen);
                        colInfo[i].fracLen = Math.max(colInfo[i].fracLen, lenInfo.fracLen);
                    } else {
                        colInfo[i].strLen = Math.max(colInfo[i].strLen, lenInfo.strLen);
                    }
                }
            }
            for (int i = 0; i < attrs.size(); i++) {
                colInfo[i].name = CommonUtils.truncateString(colInfo[i].name,maxColumnSize);
                colInfo[i].strLen = Math.min(colInfo[i].strLen,maxColumnSize);
                if (colInfo[i].useNumLen) {
                    int diff = Math.max(0,colInfo[i].length() - maxColumnSize);
                    colInfo[i].fracLen -= Math.min(diff,colInfo[i].fracLen);
                    diff = Math.max(0,colInfo[i].length() - maxColumnSize);
                    colInfo[i].intLen -= diff;
                }
            }
        }

        if (!append) {
            // Print header
            if (delimLeading) grid.append("|");
            for (int i = 0; i < attrs.size(); i++) {
                if (i > 0) grid.append("|");
                String pad = spaces.substring(0,colInfo[i].length() - colInfo[i].name.length());
                if (colInfo[i].rightJustify) grid.append(pad);
                grid.append(colInfo[i].name);
                if (!colInfo[i].rightJustify) grid.append(pad);
            }
            if (delimTrailing) grid.append("|");
            grid.append("\n");

            // Print divider
            // Print header
            if (delimLeading) grid.append("|");
            for (int i = 0; i < attrs.size(); i++) {
                if (i > 0) grid.append("|");
                for (int k = colInfo[i].length(); k > 0; k--) grid.append("-");
            }
            if (delimTrailing) grid.append("|");
            grid.append("\n");
        }

        // Print rows
        int firstRow = append ? totalRows : 0;
        if (append) {
            grid.append("\n");
        }
        for (int i = firstRow; i < allRows.size(); i++) {
            ResultSetRow row = allRows.get(i);
            if (delimLeading) grid.append("|");
            for (int k = 0; k < attrs.size(); k++) {
                if (k > 0) grid.append("|");
                DBDAttributeBinding attr = attrs.get(k);
                String displayString = getCellString(model, attr, row, displayFormat);
                if (displayString.length() >= colInfo[k].length()) {
                    displayString = CommonUtils.truncateString(displayString, colInfo[k].length());
                }

                lenInfo = getColInfo(displayString, colInfo[k].useNumLen, decimalSeparator);

                if (colInfo[k].useNumLen)
                {
                    int len = colInfo[k].intLen - lenInfo.intLen + colInfo[k].numLeftPad();
                    grid.append(spaces.substring(0,len));
                    grid.append(displayString);
                    len += displayString.length();
                    grid.append(spaces.substring(0,colInfo[k].length() - len));
                } else {
                    String pad = spaces.substring(0,colInfo[k].length() - strInfo.length());
                    if (colInfo[k].rightJustify) grid.append(pad);
                    grid.append(displayString);
                    if (!colInfo[k].rightJustify) grid.append(pad);
                }
            }
            if (delimTrailing) grid.append("|");
            grid.append("\n");
        }
        grid.setLength(grid.length() - 1); // cut last line feed

        if (append) {
            text.append(grid.toString());
        } else {
            text.setText(grid.toString());
        }

        totalRows = allRows.size();
    }

    private class ColumnInformation {

        public String name;
        public boolean useNumLen;
        public boolean rightJustify;
        public int strLen;
        public int intLen;
        public int fracLen;

        public int numLen() {
            return intLen + (fracLen > 0 ? fracLen + 1 : 0); // add dec separator if fracLen > 0
        }

        public int length() {
            return useNumLen ? Math.max(numLen(), strLen) : strLen;
        }

        public int numLeftPad() {
                return Math.max(0, strLen - numLen());
        }
    }

    private ColumnInformation getColInfo(String str, boolean useNumLen, char decimalSeparator) {
        ColumnInformation col = new ColumnInformation();
        if (str == null) { str = ""; }
        col.strLen = str.length();
        if (useNumLen) {
            col.intLen = str.indexOf(decimalSeparator);
            if (col.intLen < 0) {
                col.intLen = col.strLen;
                col.fracLen = 0;
            } else {
                col.fracLen = col.strLen - col.intLen - 1;
            }
        } else {
            for (int pos = str.indexOf('\t'); pos >= 0; pos = str.indexOf('\t',pos+1)) {
                col.strLen += tabWidth - 1;
            }
        }
        return col;
    }

    private static String getAttributeName(DBDAttributeBinding attr) {
        if (CommonUtils.isEmpty(attr.getLabel())) {
            return attr.getName();
        } else {
            return attr.getLabel();
        }
    }

    private String getCellString(ResultSetModel model, DBDAttributeBinding attr, ResultSetRow row, DBDDisplayFormat displayFormat) {
        Object cellValue = model.getCellValue(attr, row);
        if (cellValue instanceof DBDValueError) {
            return ((DBDValueError) cellValue).getErrorTitle();
        }
        String displayString = attr.getValueHandler().getValueDisplayString(attr, cellValue, displayFormat);

        if (displayString.isEmpty() &&
            showNulls &&
            DBUtils.isNullValue(cellValue))
        {
            displayString = DBConstants.NULL_VALUE_LABEL;
        }
        return displayString
            .replace('\n', TextUtils.PARAGRAPH_CHAR)
            .replace("\r", "")
            .replace((char)0, ' ');
    }

    private void printRecord() {
        String[] values = new String[attrs.size()];
        ResultSetRow currentRow = controller.getCurrentRow();

        // Calculate column widths
        int nameWidth = 4, valueWidth = 5;
        for (int i = 0; i < attrs.size(); i++) {
            DBDAttributeBinding attr = attrs.get(i);
            nameWidth = Math.max(nameWidth, getAttributeName(attr).length());
            if (currentRow != null) {
                String displayString = getCellString(model, attr, currentRow, displayFormat);
                values[i] = displayString;
                valueWidth = Math.max(valueWidth, values[i].length());
            }
        }

        // Header
        if (delimLeading) grid.append("|");
        grid.append("Name");
        grid.append(spaces.substring(0,nameWidth-4));
        grid.append("|Value");
        grid.append(spaces.substring(0,valueWidth-5));
        if (delimTrailing) grid.append("|");
        grid.append("\n");
        if (delimLeading) grid.append("|");
        for (int j = 0; j < nameWidth; j++) grid.append("-");
        grid.append("|");
        for (int j = 0; j < valueWidth; j++) grid.append("-");
        if (delimTrailing) grid.append("|");
        grid.append("\n");

        if (currentRow != null) {
            // Values
            for (int i = 0; i < attrs.size(); i++) {
                DBDAttributeBinding attr = attrs.get(i);
                String name = getAttributeName(attr);
                if (delimLeading) grid.append("|");
                grid.append(name);
                grid.append(spaces.substring(0,nameWidth - name.length()));
                grid.append("|");
                grid.append(values[i]);
                grid.append(spaces.substring(0,valueWidth - values[i].length()));
                if (delimTrailing) grid.append("|");
                grid.append("\n");
            }
        }
        grid.setLength(grid.length() - 1); // cut last line feed
        text.setText(grid.toString());
    }

    @Override
    public void formatData(boolean refreshData) {
        //controller.refreshData(null);
    }

    @Override
    public void clearMetaData() {
        colInfo = null;
        curLineRange = null;
        totalRows = 0;
    }

    @Override
    public void updateValueView() {

    }

    @Override
    public void fillMenu(@NotNull IMenuManager menu) {

    }

    @Override
    public void changeMode(boolean recordMode) {

    }

    @Override
    public void scrollToRow(@NotNull RowPosition position) {
        if (controller.isRecordMode()) {
            super.scrollToRow(position);
        } else {
            int caretOffset = text.getCaretOffset();
            if (caretOffset < 0) caretOffset = 0;
            int lineNum = text.getLineAtOffset(caretOffset);
            if (lineNum < FIRST_ROW_LINE) {
                lineNum = FIRST_ROW_LINE;
            }
            int lineOffset = text.getOffsetAtLine(lineNum);
            int xOffset = caretOffset - lineOffset;
            int totalLines = text.getLineCount();
            switch (position) {
                case FIRST:
                    lineNum = FIRST_ROW_LINE;
                    break;
                case PREVIOUS:
                    lineNum--;
                    break;
                case NEXT:
                    lineNum++;
                    break;
                case LAST:
                    lineNum = totalLines - 1;
                    break;
                case CURRENT:
                    lineNum = controller.getCurrentRow().getVisualNumber() + FIRST_ROW_LINE;
                    break;
            }
            if (lineNum < FIRST_ROW_LINE || lineNum >= totalLines) {
                return;
            }
            int newOffset = text.getOffsetAtLine(lineNum);
            newOffset += xOffset;
            text.setCaretOffset(newOffset);
            //text.setSelection(newOffset, 0);
            text.showSelection();
        }
    }

    @Nullable
    @Override
    public DBDAttributeBinding getCurrentAttribute() {
        return curAttribute;
    }

    @Nullable
    @Override
    public String copySelectionToString(ResultSetCopySettings settings) {
        return text.getSelectionText();
    }

    private static PrinterData fgPrinterData= null;

    @Override
    public void printResultSet() {
        final Shell shell = getControl().getShell();
        StyledTextPrintOptions options = new StyledTextPrintOptions();
        options.printTextFontStyle = true;
        options.printTextForeground = true;

        if (Printer.getPrinterList().length == 0) {
            UIUtils.showMessageBox(shell, "No printers", "Printers not found", SWT.ICON_ERROR);
            return;
        }

        final PrintDialog dialog = new PrintDialog(shell, SWT.PRIMARY_MODAL);
        dialog.setPrinterData(fgPrinterData);
        final PrinterData data = dialog.open();

        if (data != null) {
            final Printer printer = new Printer(data);
            final Runnable styledTextPrinter = text.print(printer, options);
            new Thread("Printing") { //$NON-NLS-1$
                public void run() {
                    styledTextPrinter.run();
                    printer.dispose();
                }
            }.start();

			/*
             * FIXME:
			 * 	Should copy the printer data to avoid threading issues,
			 *	but this is currently not possible, see http://bugs.eclipse.org/297957
			 */
            fgPrinterData = data;
            fgPrinterData.startPage = 1;
            fgPrinterData.endPage = 1;
            fgPrinterData.scope = PrinterData.ALL_PAGES;
            fgPrinterData.copyCount = 1;
        }
    }

    @Override
    protected void performHorizontalScroll(int scrollCount) {
        ScrollBar hsb = text.getHorizontalBar();
        if (hsb != null && hsb.isVisible()) {
            int curPosition = text.getHorizontalPixel();
            int pageIncrement = UIUtils.getFontHeight(text.getFont()) * 10;
            if (scrollCount > 0) {
                if (curPosition > 0) {
                    curPosition -= pageIncrement;
                }
            } else {
                curPosition += pageIncrement;
            }
            if (curPosition < 0) curPosition = 0;
            text.setHorizontalPixel(curPosition);
            //text.setHorizontalIndex();
        }
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == IFindReplaceTarget.class) {
            return adapter.cast(findReplaceTarget);
        }
        return null;
    }

    @Override
    public ISelection getSelection() {
        return new PlainTextSelectionImpl();
    }

    private class PlainTextSelectionImpl implements IResultSetSelection {

        @Nullable
        @Override
        public Object getFirstElement()
        {
            return curSelection;
        }

        @Override
        public Iterator<String> iterator()
        {
            return toList().iterator();
        }

        @Override
        public int size()
        {
            return curSelection == null ? 0 : 1;
        }

        @Override
        public Object[] toArray()
        {
            return curSelection == null ?
                new Object[0] :
                new Object[] { curSelection };
        }

        @Override
        public List<String> toList()
        {
            return curSelection == null ?
                Collections.emptyList() :
                Collections.singletonList(curSelection);
        }

        @Override
        public boolean isEmpty()
        {
            return false;
        }

        @NotNull
        @Override
        public IResultSetController getController()
        {
            return controller;
        }

        @NotNull
        @Override
        public List<DBDAttributeBinding> getSelectedAttributes() {
            if (curAttribute == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(curAttribute);
        }

        @NotNull
        @Override
        public List<ResultSetRow> getSelectedRows()
        {
            ResultSetRow currentRow = controller.getCurrentRow();
            if (currentRow == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(currentRow);
        }

        @Override
        public DBDAttributeBinding getElementAttribute(Object element) {
            return curAttribute;
        }

        @Override
        public ResultSetRow getElementRow(Object element) {
            return getController().getCurrentRow();
        }
    }

}
