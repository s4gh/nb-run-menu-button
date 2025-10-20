package io.github.s4gh.runinmenu;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import javax.swing.*;

import org.netbeans.api.project.Project;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.ui.OpenProjects;

import org.netbeans.spi.project.ProjectConfiguration;
import org.netbeans.spi.project.ProjectConfigurationProvider;

import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

import org.openide.loaders.DataObject;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.Utilities;
import org.openide.util.WeakListeners;
import org.openide.util.actions.Presenter;

@ActionID(
        category = "Run",
        id = "io.github.s4gh.runinmenu.CompactRunProfileAction"
)
@ActionRegistration(
        displayName = "#CTL_CompactRunProfileAction",
        lazy = false,
        surviveFocusChange = true
)
@ActionReference(path = "Menu", position = 9005)
@Messages("CTL_CompactRunProfileAction=Profiles")
public final class CompactRunProfileAction extends AbstractAction
        implements Presenter.Toolbar, PropertyChangeListener, org.openide.util.LookupListener {

    private JButton button;
    private JPopupMenu popup;

    private Project currentProject;
    private ProjectConfigurationProvider<ProjectConfiguration> provider;

    // --- Lookups we watch (global selection) ---
    private Lookup.Result<Project> selProjects;
    private Lookup.Result<DataObject> selFiles;

    // --- Property listeners ---
    private final PropertyChangeListener openProjectsL = evt -> {
        // also refresh when Main Project changes (fallback case)
        if (OpenProjects.PROPERTY_MAIN_PROJECT.equals(evt.getPropertyName())) {
            resolveProjectFromSelection(); // may change to the new main project
        }
    };
    private PropertyChangeListener providerL;

    @Override
    public void actionPerformed(ActionEvent e) {
        if (button != null) {
            showPopup();
        }
    }

    @Override
    public Component getToolbarPresenter() {
        if (button == null) {
            button = new JButton();
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            button.setHorizontalAlignment(SwingConstants.LEADING);
            button.setPreferredSize(new Dimension(70, button.getPreferredSize().height));
            button.setToolTipText("Run profile");

            // Show popup on click
            button.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { showPopup(); }
            });

            // --- listen to global selection (editor/Projects/Files/Favorites) ---
            Lookup actionCtx = Utilities.actionsGlobalContext();
            selProjects = actionCtx.lookupResult(Project.class);
            selFiles    = actionCtx.lookupResult(DataObject.class);
            selProjects.addLookupListener(WeakListeners.create(org.openide.util.LookupListener.class, this, selProjects));
            selFiles.addLookupListener(WeakListeners.create(org.openide.util.LookupListener.class, this, selFiles));

            // Also observe main project changes (fallback)
            OpenProjects.getDefault().addPropertyChangeListener(
                    WeakListeners.propertyChange(openProjectsL, OpenProjects.getDefault())
            );

            // Initial resolution
            resolveProjectFromSelection();
        }
        return button;
    }

    // === Global selection changed ===
    @Override
    public void resultChanged(org.openide.util.LookupEvent ev) {
        resolveProjectFromSelection();
    }

    /** Resolve project: prefer selected Project; else owner of selected file; else main project. */
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
            refreshProviderAndUI();
        } else {
            // Still refresh label/popover contents (configs might have changed)
            updateButtonLabel();
            rebuildPopup();
        }
    }

    private void refreshProviderAndUI() {
        detachProviderListener();
        provider = null;

        if (currentProject != null) {
            @SuppressWarnings("unchecked")
            ProjectConfigurationProvider<ProjectConfiguration> p =
                currentProject.getLookup().lookup(ProjectConfigurationProvider.class);
            provider = p;
            attachProviderListener();
        }

        updateButtonLabel();
        rebuildPopup();
        button.setEnabled(provider != null && provider.getConfigurations() != null
                          && !provider.getConfigurations().isEmpty());
    }

    private void attachProviderListener() {
        if (provider != null) {
            providerL = WeakListeners.propertyChange(this, provider);
            provider.addPropertyChangeListener(providerL);
        }
    }

    private void detachProviderListener() {
        if (provider != null && providerL != null) {
            provider.removePropertyChangeListener(providerL);
        }
        providerL = null;
    }

    private void updateButtonLabel() {
        String label = "()" + "\u25BE";
        if (provider != null) {
            ProjectConfiguration active = provider.getActiveConfiguration();
            if (active != null) {
                String full = active.getDisplayName();
                label = truncateName(full);
                button.setToolTipText(full);
            }
        }
        button.setText(label);
    }

    private static String truncateName(String s) {
        String arrowDown = "\u25BE";
        if (s == null) return "()" + arrowDown;
        else if (s.length() <= 7) {
            return s + arrowDown;
        } else {
            return s.replaceAll("<", "").replaceAll(">", "").substring(0, 7) + arrowDown;
        }
    }

    private void rebuildPopup() {
        popup = new JPopupMenu();

        if (provider == null || provider.getConfigurations() == null || provider.getConfigurations().isEmpty()) {
            JMenuItem none = new JMenuItem("(no conf)");
            none.setEnabled(false);
            popup.add(none);
            return;
        }

        ButtonGroup group = new ButtonGroup();
        ProjectConfiguration active = provider.getActiveConfiguration();

        for (ProjectConfiguration cfg : provider.getConfigurations()) {
            String fullName = ((ProjectConfiguration)cfg).getDisplayName();
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(fullName, cfg.equals(active));
            item.addActionListener(ev -> EventQueue.invokeLater(() -> {
                try {
                    provider.setActiveConfiguration((ProjectConfiguration)cfg);
                } catch (IllegalArgumentException | IOException ex) {
                    UIManager.getLookAndFeel().provideErrorFeedback(button);
                }
            }));
            group.add(item);
            popup.add(item);
        }

        if (provider.hasCustomizer()) {
            popup.addSeparator();
            JMenuItem custom = new JMenuItem("Customize…");
            custom.addActionListener(ev -> provider.customize());
            popup.add(custom);
        }
    }

    private void showPopup() {
        if (popup == null) {
            rebuildPopup();
        }
        popup.show(button, 0, button.getHeight());
    }

    // Listen to provider changes: active profile and list updates
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        String pn = evt.getPropertyName();
        if (ProjectConfigurationProvider.PROP_CONFIGURATION_ACTIVE.equals(pn)
                || ProjectConfigurationProvider.PROP_CONFIGURATIONS.equals(pn)) {
            updateButtonLabel();
            rebuildPopup();
        }
    }
}