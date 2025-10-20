package io.github.s4gh.runinmenu;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collection;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.Document;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.project.FileOwnerQuery;

import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ActionProvider;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.DropDownButtonFactory;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.LookupEvent;
import org.openide.util.NbBundle.Messages;
import org.openide.util.Utilities;
import org.openide.util.WeakListeners;
import org.openide.util.actions.Presenter;
import org.openide.util.lookup.Lookups;

@ActionID(
    category = "Run",
    id = "io.github.s4gh.runinmenu.RunInMenubar"
)
@ActionRegistration(
    displayName = "#CTL_RunInMenuButton",
    lazy = false
)
@ActionReferences({
    @ActionReference(path = "Menu", position = 9010)
})
@Messages("CTL_RunInMenuButton=Run")
public final class RunInMenuButton extends AbstractAction implements Presenter.Toolbar, PropertyChangeListener, org.openide.util.LookupListener {
    
    private Lookup.Result<Project> selProjects;
    private Lookup.Result<DataObject> selFiles;
    private Project currentProject;
    private final PropertyChangeListener openProjectsL = evt -> {
        // also refresh when Main Project changes (fallback case)
        if (OpenProjects.PROPERTY_MAIN_PROJECT.equals(evt.getPropertyName())) {
            resolveProjectFromSelection(); // may change to the new main project
        }
    };

    @Override
    public void actionPerformed(ActionEvent e) {
        // Not used; the button returned below handles its own click.
        runProject(ActionProvider.COMMAND_RUN);
    }

    @Override
    public Component getToolbarPresenter() {
        ImageIcon runIcon = ImageUtilities.loadImageIcon("icons/run.svg", false);
        ImageIcon debugIcon = ImageUtilities.loadImageIcon("icons/debug.svg", false);

        JPopupMenu popup = new JPopupMenu();

        JMenuItem runProjectItem = new JMenuItem("Run Project (F6)");
        runProjectItem.setIcon(runIcon);
        runProjectItem.addActionListener(ev -> runProject(ActionProvider.COMMAND_RUN));
        popup.add(runProjectItem);

        JMenuItem debugProjectItem = new JMenuItem("Debug Project (Ctrl+F5)");
        debugProjectItem.setIcon(debugIcon);
        debugProjectItem.addActionListener(ev -> runProject(ActionProvider.COMMAND_DEBUG));
        popup.add(debugProjectItem);
        
        popup.addSeparator();
             
        JMenuItem runFileItem = new JMenuItem("Run Current File (Shift+F6)");
        runFileItem.setIcon(runIcon);
        runFileItem.addActionListener(ev -> runCurrentFile(ActionProvider.COMMAND_RUN_SINGLE));
        popup.add(runFileItem);

        JMenuItem debugFileItem = new JMenuItem("Debug Current File (Ctrl+Shift+F5)");
        debugFileItem.setIcon(debugIcon);
        debugFileItem.addActionListener(ev -> runCurrentFile(ActionProvider.COMMAND_DEBUG_SINGLE));
        popup.add(debugFileItem);
        
        JButton btn = DropDownButtonFactory.createDropDownButton(runIcon, popup);
        btn.setPreferredSize(btn.getPreferredSize());
        btn.addActionListener(ev -> runProject(ActionProvider.COMMAND_RUN));
        
        // --- listen to global selection (editor/Projects/Files/Favorites) ---
        Lookup actionCtx = Utilities.actionsGlobalContext();
        selProjects = actionCtx.lookupResult(Project.class);
        selFiles = actionCtx.lookupResult(DataObject.class);
        selProjects.addLookupListener(WeakListeners.create(org.openide.util.LookupListener.class, this, selProjects));
        selFiles.addLookupListener(WeakListeners.create(org.openide.util.LookupListener.class, this, selFiles));

        // Also observe main project changes (fallback)
        OpenProjects.getDefault().addPropertyChangeListener(
                WeakListeners.propertyChange(openProjectsL, OpenProjects.getDefault())
        );
        
        resolveProjectFromSelection();
       
        paintAsToolbarBackground(btn);
        
        return btn;
    }
    
    public static Color toolbarBackground() {
        Color c = UIManager.getColor("ToolBar.background");           // FlatLaf key
        if (c == null) {
            c = UIManager.getColor("Panel.background");    // defensive fallback
        }
        if (c == null) {
            c = UIManager.getColor("control");             // last-resort Swing default
        }
        return c;
    }
    
    public static void paintAsToolbarBackground(AbstractButton b) {
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBackground(toolbarBackground());
    }

    private void runProject(String command) {
        resolveProjectFromSelection();

        if (currentProject != null) {
            ActionProvider ap = currentProject.getLookup().lookup(ActionProvider.class);
            if (ap != null && ap.isActionEnabled(command, Lookup.EMPTY)) {
                ap.invokeAction(command, Lookup.EMPTY);
            }
        }
    }
    
    private static DataObject currentEditorDataObject() {
        JEditorPane pane = (JEditorPane) EditorRegistry.lastFocusedComponent();
        if (pane == null) {
            return null;
        }
        Document doc = pane.getDocument();
        if (doc == null) {
            return null;
        }

        Object sdp = doc.getProperty(Document.StreamDescriptionProperty);
        if (sdp instanceof DataObject) {
            return (DataObject) sdp;
        } else if (sdp instanceof FileObject) {
            try {
                return DataObject.find((FileObject) sdp);
            } catch (DataObjectNotFoundException ex) {
                return null;
            }
        }
        return null;
    }
    
    private void runCurrentFile(String command){
        DataObject dobj = currentEditorDataObject();
        if (dobj == null) {
            return;
        }
        FileObject fo = dobj.getPrimaryFile();
        Project prj = FileOwnerQuery.getOwner(fo);
        if (prj == null) {
            return;
        }
        ActionProvider ap = prj.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            return;
        }

        // Run on the EDT like ActionProvider expects
        SwingUtilities.invokeLater(() -> {
            Lookup actionCtx = Lookups.fixed(dobj);
            if (ap.isActionEnabled(command, actionCtx)) {
                ap.invokeAction(command, actionCtx);
            }
        });
    }
    
    private void resolveProjectFromSelection() {
        Project selected = null;

        // 1) direct Project from context (e.g., project node selected)
        Collection<? extends Project> ps = selProjects != null ? selProjects.allInstances() : java.util.List.of();
        if (!ps.isEmpty()) {
            selected = ps.iterator().next();
        }

        // 2) derive from selected file (editor/Files/Favorites)
        if (selected == null && selFiles != null) {
            Collection<? extends DataObject> datas = selFiles.allInstances();
            if (!datas.isEmpty()) {
                DataObject dob = datas.iterator().next();
                if (dob != null && dob.getPrimaryFile() != null) {
                    selected = FileOwnerQuery.getOwner(dob.getPrimaryFile());
                }
            }
        }

        // 3) fallback: main project (may be null if user never set one)
        if (selected == null) {
            selected = OpenProjects.getDefault().getMainProject();
        }

        // Apply if changed
        if (!Objects.equals(selected, currentProject)) {
            currentProject = selected;
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        resolveProjectFromSelection();
    }

    @Override
    public void resultChanged(LookupEvent le) {
        resolveProjectFromSelection();
    }
}
