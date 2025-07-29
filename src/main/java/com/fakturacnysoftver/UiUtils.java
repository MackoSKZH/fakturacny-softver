package com.fakturacnysoftver;

import javafx.scene.control.TableColumn;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.cell.TextFieldTableCell;

import javafx.util.converter.DoubleStringConverter;
import javafx.util.converter.IntegerStringConverter;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

public class UiUtils {

    public static void setupItemColumns(TableColumn<FakturaData.Item, String> popis,
                                        TableColumn<FakturaData.Item, Integer> mnoz,
                                        TableColumn<FakturaData.Item, Double> cena,
                                        Runnable recalc) {
        popis.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().getPopis()));
        popis.setCellFactory(TextFieldTableCell.forTableColumn());
        popis.setOnEditCommit(e -> {
            e.getRowValue().setPopis(e.getNewValue());
            recalc.run();
        });

        mnoz.setCellValueFactory(param -> new javafx.beans.property.SimpleObjectProperty<>(param.getValue().getMnozstvo()));
        mnoz.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        mnoz.setOnEditCommit(e -> {
            e.getRowValue().setMnozstvo(e.getNewValue());
            recalc.run();
        });

        cena.setCellValueFactory(param -> new javafx.beans.property.SimpleObjectProperty<>(param.getValue().getCena()));
        cena.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        cena.setOnEditCommit(e -> {
            e.getRowValue().setCena(e.getNewValue());
            recalc.run();
        });
    }

    public static UserData showUserDialog(UserData data, Window owner) {
        Dialog<UserData> dialog = new Dialog<>();
        dialog.setTitle("Upraviť údaje");
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);

        ButtonType ulozitBtn = new ButtonType("Uložiť", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ulozitBtn, ButtonType.CANCEL);

        TextField tfNazov = new TextField(data.getNazov());
        TextField tfSidlo = new TextField(data.getSidlo());
        TextField tfIco = new TextField(data.getIco());
        TextField tfDic = new TextField(data.getDic());
        TextField tfKontakt = new TextField(data.getKontakt());
        TextField tfZastupeny = new TextField(data.getZastupeny());

        TextField tfBankaNazov = new TextField(data.getBankaNazov());
        TextField tfIban = new TextField(data.getBankaIban());
        TextField tfSwift = new TextField(data.getBankaSwift());

        CheckBox cbPlatcaDph = new CheckBox("Platiteľ DPH");
        cbPlatcaDph.setSelected(data.isPlatcaDPH());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;
        grid.addRow(row++, new Label("Názov:"), tfNazov);
        grid.addRow(row++, new Label("Sídlo:"), tfSidlo);
        grid.addRow(row++, new Label("IČO:"), tfIco);
        grid.addRow(row++, new Label("DIČ:"), tfDic);
        grid.addRow(row++, new Label("Kontakt:"), tfKontakt);
        grid.addRow(row++, new Label("Zastúpený:"), tfZastupeny);

        grid.addRow(row++, new Label("Banka:"), tfBankaNazov);
        grid.addRow(row++, new Label("IBAN:"), tfIban);
        grid.addRow(row++, new Label("BIC/SWIFT:"), tfSwift);

        grid.addRow(row++, cbPlatcaDph);

        GridPane.setHgrow(tfNazov, Priority.ALWAYS);
        GridPane.setHgrow(tfSidlo, Priority.ALWAYS);
        GridPane.setHgrow(tfIco, Priority.ALWAYS);
        GridPane.setHgrow(tfDic, Priority.ALWAYS);
        GridPane.setHgrow(tfKontakt, Priority.ALWAYS);
        GridPane.setHgrow(tfZastupeny, Priority.ALWAYS);
        GridPane.setHgrow(tfBankaNazov, Priority.ALWAYS);
        GridPane.setHgrow(tfIban, Priority.ALWAYS);
        GridPane.setHgrow(tfSwift, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == ulozitBtn) {
                data.setNazov(tfNazov.getText());
                data.setSidlo(tfSidlo.getText());
                data.setIco(tfIco.getText());
                data.setDic(tfDic.getText());
                data.setKontakt(tfKontakt.getText());
                data.setZastupeny(tfZastupeny.getText());
                data.setPlatcaDPH(cbPlatcaDph.isSelected());

                data.setBankaNazov(tfBankaNazov.getText());
                data.setBankaIban(tfIban.getText());
                data.setBankaSwift(tfSwift.getText());

                return data;
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }

}
