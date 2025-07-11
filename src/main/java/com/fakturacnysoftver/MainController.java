package com.fakturacnysoftver;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalDate;

public class MainController {

    @FXML private TextField tfCustomerName;
    @FXML private TextField tfCustomerAddress;
    @FXML private DatePicker dpDate;
    @FXML private TableView<FakturaData.Item> tblItems;
    @FXML private TableColumn<FakturaData.Item, String> colPopis;
    @FXML private TableColumn<FakturaData.Item, Integer> colMnoz;
    @FXML private TableColumn<FakturaData.Item, Double> colCena;
    @FXML private Label lblSubtotal;
    @FXML private Label lblDph;
    @FXML private Label lblTotal;

    private final ObservableList<FakturaData.Item> items = FXCollections.observableArrayList();
    private final FarnostData farnost = StorageManager.loadFarnost();

    @FXML
    public void initialize() {
        this.dpDate.setValue(LocalDate.now());
        this.tblItems.setItems(this.items);

        // Bindings pre existujúce stĺpce
        this.colPopis.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getPopis()));
        this.colMnoz.setCellValueFactory(cellData -> new javafx.beans.property.SimpleObjectProperty<>(cellData.getValue().getMnozstvo()));
        this.colCena.setCellValueFactory(cellData -> new javafx.beans.property.SimpleObjectProperty<>(cellData.getValue().getCena()));

        // colPopis
        this.colPopis.setCellFactory(column -> new EditingCell<>(new javafx.util.converter.DefaultStringConverter()));
        this.colPopis.setOnEditCommit(event -> {
            event.getRowValue().setPopis(event.getNewValue());
            this.recalculate();
            this.tblItems.refresh();
        });

        // colMnoz
        this.colMnoz.setCellFactory(column -> new EditingCell<>(new javafx.util.converter.IntegerStringConverter()));
        this.colMnoz.setOnEditCommit(event -> {
            event.getRowValue().setMnozstvo(event.getNewValue());
            this.recalculate();
            this.tblItems.refresh();
        });

        // colCena
        this.colCena.setCellFactory(column -> new EditingCell<>(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : String.format("%.2f", value);
            }

            @Override
            public Double fromString(String input) {
                if (input == null || input.trim().isEmpty()) {
                    return 0.0;
                }
                input = input.replace(",", ".").trim();
                try {
                    return Double.parseDouble(input);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
        }));
        this.colCena.setOnEditCommit(event -> {
            event.getRowValue().setCena(event.getNewValue());
            this.recalculate();
            this.tblItems.refresh();
        });

        // ----- NOVÉ STĹPCE -----

        TableColumn<FakturaData.Item, String> colZaklad = new TableColumn<>("Základ");
        colZaklad.setCellValueFactory(data -> {
            double zaklad = data.getValue().getZaklad();
            return new javafx.beans.property.SimpleStringProperty(String.format("%.2f €", zaklad));
        });

        TableColumn<FakturaData.Item, String> colDph = new TableColumn<>("DPH");
        colDph.setCellValueFactory(data -> {
            double dph = data.getValue().getDph();
            return new javafx.beans.property.SimpleStringProperty(String.format("%.2f €", dph));
        });

        TableColumn<FakturaData.Item, String> colSpolu = new TableColumn<>("Spolu s DPH");
        colSpolu.setCellValueFactory(data -> {
            double spolu = data.getValue().getCenaSDph();
            return new javafx.beans.property.SimpleStringProperty(String.format("%.2f €", spolu));
        });

        // Pridanie nových stĺpcov do tabuľky
        this.tblItems.getColumns().addAll(colZaklad, colDph, colSpolu);

        this.tblItems.setEditable(true);
        this.recalculate();
    }


    @FXML
    private void handleAddRow(ActionEvent e) {
        FakturaData.Item newItem = new FakturaData.Item("", 1, 0.0);
        this.items.add(newItem);

        int lastIndex = this.items.size() - 1;
        this.tblItems.layout();

        this.tblItems.getSelectionModel().select(lastIndex);
        this.tblItems.scrollTo(lastIndex);

        this.tblItems.edit(lastIndex, this.colPopis);
    }

    @FXML
    private void handleExport(ActionEvent e) {
        FakturaData faktura = this.buildFaktura();
        File pdf = PDFGenerator.exportToPdf(faktura, this.farnost, this.getWindow());
        assert pdf != null;
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "PDF uložené: " + pdf.getAbsolutePath());
        alert.showAndWait();
    }

    @FXML
    private void handlePrint(ActionEvent e) {
        FakturaData faktura = this.buildFaktura();
        PDFGenerator.printFaktura(faktura, this.farnost, this.getWindow());
    }

    @FXML
    private void handleEditFarnost(ActionEvent e) {
        FarnostData edited = UiUtils.showFarnostDialog(this.farnost, this.getWindow());
        if (edited != null) {
            StorageManager.saveFarnost(edited);
        }
    }

    private FakturaData buildFaktura() {
        return new FakturaData(this.tfCustomerName.getText(), this.tfCustomerAddress.getText(), this.dpDate.getValue(), this.items);
    }

    private Window getWindow() {
        return this.tfCustomerName.getScene().getWindow();
    }

    private void recalculate() {
        double subtotal = this.items.stream().mapToDouble(i -> i.getMnozstvo() * i.getCena()).sum();
        double dph = subtotal * 0.23;
        this.lblSubtotal.setText(String.format("%.2f €", subtotal));
        this.lblDph.setText(String.format("%.2f €", dph));
        this.lblTotal.setText(String.format("%.2f €", subtotal + dph));
    }

    private class EditingCell<T> extends TableCell<FakturaData.Item, T> {

        private final TextField textField = new TextField();
        private final javafx.util.StringConverter<T> converter;

        EditingCell(javafx.util.StringConverter<T> converter) {
            this.converter = converter;

            this.textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && this.isEditing()) {
                    this.commitEdit(converter.fromString(this.textField.getText()));
                }
            });

            this.textField.setOnAction(e -> this.commitEdit(converter.fromString(this.textField.getText())));
        }

        @Override
        public void startEdit() {
            super.startEdit();
            this.textField.setText(this.converter.toString(this.getItem()));
            this.setGraphic(this.textField);
            this.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            this.textField.requestFocus();
            this.textField.selectAll();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            this.setText(this.converter.toString(this.getItem()));
            this.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                this.setText(null);
                this.setGraphic(null);
            } else if (this.isEditing()) {
                this.textField.setText(this.converter.toString(item));
                this.setGraphic(this.textField);
                this.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                this.setText(this.converter.toString(item));
                this.setContentDisplay(ContentDisplay.TEXT_ONLY);
            }
        }
    }
}