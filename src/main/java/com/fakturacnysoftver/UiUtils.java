package com.fakturacnysoftver;

import javafx.scene.control.*;
import javafx.util.converter.DoubleStringConverter;
import javafx.util.converter.IntegerStringConverter;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.function.Consumer;

public class UiUtils {

    public static void setupItemColumns(TableColumn<FakturaData.Item,String> popis,
                                        TableColumn<FakturaData.Item,Integer> mnoz,
                                        TableColumn<FakturaData.Item,Double> cena,
                                        Runnable recalc) {
        popis.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().popis()));
        popis.setCellFactory(TextFieldTableCell.forTableColumn());
        popis.setOnEditCommit(e -> {
            e.getRowValue().popis = e.getNewValue();
            recalc.run();
        });
        mnoz.setCellValueFactory(param -> new javafx.beans.property.SimpleObjectProperty<>(param.getValue().mnozstvo()));
        mnoz.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        mnoz.setOnEditCommit(e -> {
            e.getRowValue().mnozstvo = e.getNewValue();
            recalc.run();
        });
        cena.setCellValueFactory(param -> new javafx.beans.property.SimpleObjectProperty<>(param.getValue().cena()));
        cena.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        cena.setOnEditCommit(e -> {
            e.getRowValue().cena = e.getNewValue();
            recalc.run();
        });
    }

    public static FarnostData showFarnostDialog(FarnostData data, Window owner) {
        // TODO: create modal dialog (TextInputDialog)
        return null;
    }
}