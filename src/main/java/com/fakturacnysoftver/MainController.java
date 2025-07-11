package com.fakturacnysoftver;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
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
        dpDate.setValue(LocalDate.now());
        tblItems.setItems(items);
        // Property bindings, cell factories & editable rows by UiUtils helper
        UiUtils.setupItemColumns(colPopis, colMnoz, colCena, this::recalculate);
        recalculate();
    }

    @FXML
    private void handleAddRow(ActionEvent e) {
        items.add(new FakturaData.Item("", 1, 0.0));
    }

    @FXML
    private void handleExport(ActionEvent e) {
        FakturaData faktura = buildFaktura();
        File pdf = PDFGenerator.exportToPdf(faktura, farnost, getWindow());
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "PDF uložené: " + pdf.getAbsolutePath());
        alert.showAndWait();
    }

    @FXML
    private void handlePrint(ActionEvent e) {
        FakturaData faktura = buildFaktura();
        PDFGenerator.printFaktura(faktura, farnost, getWindow());
    }

    @FXML
    private void handleEditFarnost(ActionEvent e) {
        FarnostData edited = UiUtils.showFarnostDialog(farnost, getWindow());
        if (edited != null) {
            StorageManager.saveFarnost(edited);
        }
    }

    private FakturaData buildFaktura() {
        return new FakturaData(tfCustomerName.getText(), tfCustomerAddress.getText(), dpDate.getValue(), items);
    }

    private Window getWindow() {
        return tfCustomerName.getScene().getWindow();
    }

    private void recalculate() {
        double subtotal = items.stream().mapToDouble(i -> i.getMnozstvo() * i.getCena()).sum();
        double dph = subtotal * 0.20;
        lblSubtotal.setText(String.format("%.2f €", subtotal));
        lblDph.setText(String.format("%.2f €", dph));
        lblTotal.setText(String.format("%.2f €", subtotal + dph));
    }
}