package projector.controller;

import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import projector.application.ProjectionScreenSettings;
import projector.application.ProjectionType;
import projector.application.ScreenProjectionAction;
import projector.application.ScreenProjectionType;
import projector.application.Settings;
import projector.controller.util.ProjectionScreenHolder;
import projector.model.Bible;
import projector.service.ServiceManager;

import java.util.ArrayList;
import java.util.List;

import static projector.controller.util.ControllerUtil.getFxmlLoader;
import static projector.controller.util.ControllerUtil.getStageWithRoot;

public class ProjectionTypeController {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionTypeController.class);
    public VBox vBox;
    public CheckBox focusOnSongPartCheckBox;
    public CheckBox guideViewCheckBox;
    private ProjectionScreenHolder projectionScreenHolder;

    public void setProjectionScreenHolder(ProjectionScreenHolder projectionScreenHolder) {
        this.projectionScreenHolder = projectionScreenHolder;
        ProjectionScreenSettings projectionScreenSettings = projectionScreenHolder.getProjectionScreenSettings();
        focusOnSongPartCheckBox.setSelected(projectionScreenSettings.isFocusOnSongPart());
        addProjectionTypesToVBox(projectionScreenSettings);
        focusOnSongPartCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> projectionScreenSettings.setFocusOnSongPart(newValue));
        guideViewCheckBox.setSelected(projectionScreenSettings.isGuideView());
        guideViewCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> projectionScreenSettings.setGuideView(newValue));
    }

    private void addProjectionTypesToVBox(ProjectionScreenSettings projectionScreenSettings) {
        List<ScreenProjectionType> screenProjectionTypes = projectionScreenSettings.getScreenProjectionTypes();
        List<ProjectionType> projectionTypes = getProjectionTypes();
        List<ScreenProjectionType> linkedScreenProjectionTypes = getLinkedScreenProjectionTypes(screenProjectionTypes, projectionTypes);
        for (ScreenProjectionType screenProjectionType : linkedScreenProjectionTypes) {
            addProjectionTypeToVBox(screenProjectionType);
        }
    }

    private List<ScreenProjectionType> getLinkedScreenProjectionTypes(List<ScreenProjectionType> screenProjectionTypes, List<ProjectionType> projectionTypes) {
        ArrayList<ScreenProjectionType> linkedScreenProjectionTypes = new ArrayList<>();
        for (ProjectionType projectionType : projectionTypes) {
            boolean found = false;
            for (ScreenProjectionType screenProjectionType : screenProjectionTypes) {
                if (screenProjectionType.getProjectionType().equals(projectionType)) {
                    linkedScreenProjectionTypes.add(screenProjectionType);
                    found = true;
                    break;
                }
            }
            if (!found) {
                ScreenProjectionType screenProjectionType = new ScreenProjectionType();
                screenProjectionType.setProjectionType(projectionType);
                linkedScreenProjectionTypes.add(screenProjectionType);
                screenProjectionTypes.add(screenProjectionType);
            }
        }
        return linkedScreenProjectionTypes;
    }

    private void addProjectionTypeToVBox(ScreenProjectionType screenProjectionType) {
        HBox hBox = new HBox();
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.setSpacing(10);

        ComboBox<ScreenProjectionAction> actionComboBox = new ComboBox<>();
        actionComboBox.getItems().addAll(ScreenProjectionAction.getScreenProjectionActions());
        SingleSelectionModel<ScreenProjectionAction> selectionModel = actionComboBox.getSelectionModel();
        selectionModel.select(screenProjectionType.getScreenProjectionAction());
        selectionModel.selectedItemProperty().addListener((observable, oldValue, newValue) -> screenProjectionType.setScreenProjectionAction(newValue));

        Label label = new Label(screenProjectionType.getProjectionType() + "");
        ObservableList<Node> hBoxChildren = hBox.getChildren();
        hBoxChildren.addAll(label, actionComboBox);
        handleInitialization_bible(screenProjectionType, hBoxChildren);
        vBox.getChildren().add(hBox);
    }

    private void handleInitialization_bible(ScreenProjectionType screenProjectionType, ObservableList<Node> hBoxChildren) {
        if (screenProjectionType.getProjectionType() == ProjectionType.BIBLE) {
            Button parallelButton = new Button(Settings.getInstance().getResourceBundle().getString("Parallel"));
            parallelButton.setOnAction(e -> openProjectionParallelBibles());
            hBoxChildren.add(parallelButton);
        }
    }

    public void openProjectionParallelBibles() {
        try {
            FXMLLoader loader = getFxmlLoader("ProjectionParallelBibles");
            Pane root = loader.load();
            ProjectionParallelBiblesController projectionParallelBiblesController = loader.getController();
            Stage stage = getStageWithRoot(getClass(), root);
            stage.setTitle(Settings.getInstance().getResourceBundle().getString("Parallel"));
            List<Bible> bibles = ServiceManager.getBibleService().findAll();
            projectionParallelBiblesController.initialize(bibles, projectionScreenHolder.getProjectionScreenSettings(), stage);
            stage.show();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    private List<ProjectionType> getProjectionTypes() {
        List<ProjectionType> projectionTypes = new ArrayList<>();
        projectionTypes.add(ProjectionType.BIBLE);
        projectionTypes.add(ProjectionType.SONG);
        projectionTypes.add(ProjectionType.REFERENCE);
        projectionTypes.add(ProjectionType.COUNTDOWN_TIMER);
        projectionTypes.add(ProjectionType.IMAGE);
        return projectionTypes;
    }

    public static ProjectionTypeController openProjectionType(Class<?> aClass, ProjectionScreenHolder projectionScreenHolder) {
        try {
            FXMLLoader loader = getFxmlLoader("ProjectionType");
            Pane root = loader.load();
            ProjectionTypeController projectionTypeController = loader.getController();
            Stage stage = getStageWithRoot(aClass, root);
            stage.setTitle(Settings.getInstance().getResourceBundle().getString("Projection type"));
            stage.setOnCloseRequest(event -> projectionScreenHolder.getProjectionScreenSettings().save());
            projectionTypeController.setProjectionScreenHolder(projectionScreenHolder);
            stage.show();
            return projectionTypeController;
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return null;
        }
    }
}
