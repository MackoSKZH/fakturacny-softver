package com.fakturacnysoftver;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TableView;
import javafx.scene.control.Label;
import javafx.scene.control.Alert;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.ContentDisplay;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalDate;
import java.util.Collections;

public class MainController {

    @FXML private TextField tfCustomerName;
    @FXML private TextField tfCustomerAddress;
    @FXML private DatePicker dpDate;
    @FXML private DatePicker dpDatumDodania;
    @FXML private TableView<FakturaData.Item> tblItems;
    @FXML private Label lblSubtotal;
    @FXML private Label lblDph;
    @FXML private Label lblTotal;
    @FXML private TextField tfCustomerIco;
    @FXML private TextField tfCustomerDic;
    @FXML private TextField tfCustomerIcdph;
    @FXML private TextField tfCustomerTelefon;
    @FXML private TextField tfCustomerEmail;
    @FXML private TextField tfBankaNazov;
    @FXML private TextField tfBankaIban;
    @FXML private TextField tfBankaSwift;
    @FXML private TextField tfVarSymbol;

    private final ObservableList<FakturaData.Item> items = FXCollections.observableArrayList();
    private UserData user = StorageManager.loadUser();

    @FXML
    public void initialize() {
        this.dpDate.setValue(LocalDate.now());
        this.dpDatumDodania.setValue(LocalDate.now());
        this.tblItems.setItems(this.items);
        this.tblItems.setEditable(true);

        this.setupTableColumns(this.user.isPlatcaDPH());

        this.items.addListener((javafx.collections.ListChangeListener<FakturaData.Item>)change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (FakturaData.Item it : change.getAddedSubList()) {
                        this.addListenersToItem(it);
                    }
                }
                this.recalculate();
            }
        });

        for (FakturaData.Item it : this.items) {
            this.addListenersToItem(it);
        }

        this.recalculate();
    }

    @FXML
    private void handleAddRow(ActionEvent e) {
        FakturaData.Item newItem = new FakturaData.Item("", 1, 0.0);
        this.addListenersToItem(newItem);
        this.items.add(newItem);

        int lastIndex = this.items.size() - 1;
        this.tblItems.layout();
        this.tblItems.getSelectionModel().select(lastIndex);
        this.tblItems.scrollTo(lastIndex);
        this.tblItems.edit(lastIndex, this.tblItems.getColumns().get(1)); // pravdepodobne Popis
    }

    @FXML
    private void handleExport(ActionEvent e) {
        FakturaData faktura = this.buildFaktura();
        File pdf = PDFGenerator.exportToPdf(faktura, this.user, this.getWindow());
        if (pdf != null) {
            new Alert(Alert.AlertType.INFORMATION, "PDF uložené: " + pdf.getAbsolutePath()).showAndWait();
        }
    }

    @FXML
    private void handlePrint(ActionEvent e) {
        FakturaData faktura = this.buildFaktura();
        PDFGenerator.printFaktura(faktura, this.user);
    }

    @FXML
    private void handleEditUser(ActionEvent e) {
        UserData edited = UiUtils.showUserDialog(this.user, this.getWindow());
        if (edited != null) {
            StorageManager.saveUser(edited);
            this.user = edited;

            this.setupTableColumns(this.user.isPlatcaDPH());
            this.recalculate();
        }
    }

    @FXML
    private void handleDeleteSelected(ActionEvent e) {
        this.items.removeIf(FakturaData.Item::isSelected);
        this.recalculate();
    }

    private void setupTableColumns(boolean jePlatca) {
        this.tblItems.getColumns().clear();

        TableColumn<FakturaData.Item, Boolean> colSelect = new TableColumn<>("");
        colSelect.setCellValueFactory(data -> data.getValue().selectedProperty());
        colSelect.setCellFactory(CheckBoxTableCell.forTableColumn(colSelect));
        colSelect.setPrefWidth(40);

        TableColumn<FakturaData.Item, String> colPopis = new TableColumn<>("Popis");
        colPopis.setMinWidth(200);
        colPopis.setCellValueFactory(data -> data.getValue().popisProperty());
        colPopis.setCellFactory(column -> new EditingCell<>(new javafx.util.converter.DefaultStringConverter()));
        colPopis.setOnEditCommit(e -> e.getRowValue().setPopis(e.getNewValue()));

        TableColumn<FakturaData.Item, Integer> colMnoz = new TableColumn<>("Ks");
        colMnoz.setCellValueFactory(data -> data.getValue().mnozstvoProperty().asObject());
        colMnoz.setCellFactory(column -> new EditingCell<>(new javafx.util.converter.IntegerStringConverter()));
        colMnoz.setOnEditCommit(e -> e.getRowValue().setMnozstvo(e.getNewValue()));

        TableColumn<FakturaData.Item, Double> colCena = new TableColumn<>("Cena");
        colCena.setCellValueFactory(data -> data.getValue().cenaProperty().asObject());
        colCena.setCellFactory(column -> new EditingCell<>(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Double val) {
                return val == null ? "" : String.format("%.2f", val);
            }

            @Override
            public Double fromString(String s) {
                if (s == null || s.isBlank()) {
                    return 0.0;
                }
                try {
                    return Double.parseDouble(s.replace(",", ".").trim());
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
        }));
        colCena.setOnEditCommit(e -> e.getRowValue().setCena(e.getNewValue()));

        Collections.addAll(this.tblItems.getColumns(), colSelect, colPopis, colMnoz, colCena);

        if (jePlatca) {
            TableColumn<FakturaData.Item, String> colZaklad = new TableColumn<>("Základ");
            colZaklad.setCellValueFactory(data ->
                    Bindings.createStringBinding(() ->
                                    String.format("%.2f €", data.getValue().getZaklad()),
                            data.getValue().cenaProperty(), data.getValue().mnozstvoProperty()));

            TableColumn<FakturaData.Item, String> colDph = new TableColumn<>("DPH");
            colDph.setCellValueFactory(data ->
                    Bindings.createStringBinding(() ->
                                    String.format("%.2f €", data.getValue().getDph()),
                            data.getValue().cenaProperty(), data.getValue().mnozstvoProperty()));

            TableColumn<FakturaData.Item, String> colSpolu = new TableColumn<>("Spolu s DPH");
            colSpolu.setCellValueFactory(data ->
                    Bindings.createStringBinding(() ->
                                    String.format("%.2f €", data.getValue().getCenaSDph()),
                            data.getValue().cenaProperty(), data.getValue().mnozstvoProperty()));

            Collections.addAll(this.tblItems.getColumns(), colZaklad, colDph, colSpolu);
        }
    }

    private FakturaData buildFaktura() {
        FakturaData faktura = new FakturaData(
                this.tfCustomerName.getText(),
                this.tfCustomerAddress.getText(),
                this.dpDate.getValue(),
                this.items
        );

        faktura.setOdberatelIco(this.tfCustomerIco.getText());
        faktura.setOdberatelDic(this.tfCustomerDic.getText());
        faktura.setOdberatelIcdph(this.tfCustomerIcdph.getText());
        faktura.setOdberatelTelefon(this.tfCustomerTelefon.getText());
        faktura.setOdberatelEmail(this.tfCustomerEmail.getText());

        faktura.setDatumDodania(this.dpDatumDodania.getValue());

        return faktura;
    }

    private Window getWindow() {
        return this.tfCustomerName.getScene().getWindow();
    }

    private void recalculate() {
        boolean jePlatca = this.user.isPlatcaDPH();
        double subtotal = this.items.stream().mapToDouble(i -> i.getMnozstvo() * i.getCena()).sum();
        double dph = jePlatca ? subtotal * 0.23 : 0.0;

        this.lblSubtotal.setText(String.format("%.2f €", subtotal));
        this.lblDph.setText(String.format("%.2f €", dph));
        this.lblTotal.setText(String.format("%.2f €", subtotal + dph));
    }

    private static class EditingCell<T> extends TableCell<FakturaData.Item, T> {
        private final TextField textField = new TextField();
        private final javafx.util.StringConverter<T> converter;

        EditingCell(javafx.util.StringConverter<T> converter) {
            this.converter = converter;
            this.textField.setOnAction(e -> this.commitEdit(converter.fromString(this.textField.getText())));
            this.textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && this.isEditing()) {
                    this.commitEdit(converter.fromString(this.textField.getText()));
                }
            });
        }

        @Override public void startEdit() {
            super.startEdit();
            this.textField.setText(this.converter.toString(this.getItem()));
            this.setGraphic(this.textField);
            this.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            this.textField.requestFocus();
        }

        @Override public void cancelEdit() {
            super.cancelEdit();
            this.setText(this.converter.toString(this.getItem()));
            this.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        @Override protected void updateItem(T item, boolean empty) {
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

    private void addListenersToItem(FakturaData.Item item) {
        item.cenaProperty().addListener((obs, oldVal, newVal) -> this.recalculate());
        item.mnozstvoProperty().addListener((obs, oldVal, newVal) -> this.recalculate());
    }
}