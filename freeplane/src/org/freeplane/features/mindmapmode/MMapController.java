/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Joerg Mueller, Daniel Polansky, Christian Foltin, Dimitry Polivaev
 *
 *  This file is created by Dimitry Polivaev in 2008.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freeplane.features.mindmapmode;

import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.swing.JOptionPane;

import org.freeplane.core.Compat;
import org.freeplane.core.controller.Controller;
import org.freeplane.core.enums.ResourceControllerProperties;
import org.freeplane.core.frame.ViewController;
import org.freeplane.core.modecontroller.MapController;
import org.freeplane.core.modecontroller.ModeController;
import org.freeplane.core.model.EncryptionModel;
import org.freeplane.core.model.MapModel;
import org.freeplane.core.model.NodeModel;
import org.freeplane.core.resources.IFreeplanePropertyListener;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.ui.components.OptionalDontShowMeAgainDialog;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.url.UrlManager;
import org.freeplane.core.util.LogTool;
import org.freeplane.features.mindmapmode.file.MFileManager;
import org.freeplane.n3.nanoxml.XMLParseException;

/**
 * @author Dimitry Polivaev
 */
public class MMapController extends MapController {
	static private DeleteAction delete;
	private static final String EXPECTED_START_STRINGS[] = { "<map version=\"" + ResourceControllerProperties.XML_VERSION + "\"",
	        "<map version=\"0.7.1\"" };
	private static final String FREEPLANE_VERSION_UPDATER_XSLT = "/xslt/freeplane_version_updater.xslt";
	public static final int NEW_CHILD = 2;
	public static final int NEW_CHILD_WITHOUT_FOCUS = 1;
	public static final int NEW_SIBLING_BEFORE = 4;
	public static final int NEW_SIBLING_BEHIND = 3;
	static private NewChildAction newChild;
	private static IFreeplanePropertyListener sSaveIdPropertyChangeListener;

	public MMapController(final MModeController modeController) {
		super(modeController);
		if (sSaveIdPropertyChangeListener == null) {
			sSaveIdPropertyChangeListener = new IFreeplanePropertyListener() {
				public void propertyChanged(final String propertyName, final String newValue, final String oldValue) {
					if (propertyName.equals("save_only_intrisically_needed_ids")) {
						MapController.setSaveOnlyIntrinsicallyNeededIds(Boolean.valueOf(newValue).booleanValue());
					}
				}
			};
			ResourceController.getResourceController().addPropertyChangeListenerAndPropagate(
			    sSaveIdPropertyChangeListener);
		}
		createActions(modeController);
	}

	public NodeModel addNewNode(final int newNodeMode, final KeyEvent e) {
		return newChild.addNewNode(newNodeMode, e);
	}

	public NodeModel addNewNode(final NodeModel parent, final int index, final boolean newNodeIsLeft) {
		return newChild.addNewNode(parent, index, newNodeIsLeft);
	}

	/**
	 * Return false if user has canceled.
	 */
	@Override
	public boolean close(final boolean force) {
		final MapModel map = getController().getMap();
		if (!force && !map.isSaved()) {
			
			final String text = ResourceController.getText("save_unsaved") + "\n" + map.getTitle();
			final String title = UITools.removeMnemonic(ResourceController.getText("save"));
			final int returnVal = JOptionPane.showOptionDialog(getController().getViewController().getContentPane(),
			    text, title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, null, null);
			if (returnVal == JOptionPane.YES_OPTION) {
				final boolean savingNotCancelled = ((MFileManager) UrlManager.getController(getModeController()))
				    .save(map);
				if (!savingNotCancelled) {
					return false;
				}
			}
			else if ((returnVal == JOptionPane.CANCEL_OPTION) || (returnVal == JOptionPane.CLOSED_OPTION)) {
				return false;
			}
		}
		return super.close(force);
	}

	private void createActions(final MModeController modeController) {
		final Controller controller = modeController.getController();
		modeController.putAction("newMap", new NewMapAction(controller));
		modeController.putAction("newSibling", new NewSiblingAction(controller));
		modeController.putAction("newPreviousSibling", new NewPreviousSiblingAction(controller));
		newChild = new NewChildAction(controller);
		modeController.putAction("newChild", newChild);
		delete = new DeleteAction(controller);
		modeController.putAction("deleteChild", delete);
		modeController.putAction("undoableToggleFolded", new ToggleFoldedAction(controller));
		modeController.putAction("undoableToggleChildrenFolded", new ToggleChildrenFoldedAction(controller));
		modeController.putAction("nodeUp", new NodeUpAction(controller));
		modeController.putAction("nodeDown", new NodeDownAction(controller));
	}

	public void deleteNode(final NodeModel node) {
		delete.delete(node);
	}

	/**
	 */
	public void deleteWithoutUndo(final NodeModel selectedNode) {
		final NodeModel oldParent = selectedNode.getParentNode();
		firePreNodeDelete(oldParent, selectedNode, oldParent.getIndex(selectedNode));
		final MapModel map = selectedNode.getMap();
		setSaved(map, false);
		oldParent.remove(selectedNode);
		fireNodeDeleted(oldParent, selectedNode, oldParent.getIndex(selectedNode));
	}

	public MModeController getMModeController() {
		return (MModeController) getModeController();
	}

	@Override
	public void insertNodeIntoWithoutUndo(final NodeModel newNode, final NodeModel parent, final int index) {
		setSaved(parent.getMap(), false);
		super.insertNodeIntoWithoutUndo(newNode, parent, index);
	}

	public boolean isWriteable(final NodeModel targetNode) {
		final EncryptionModel encryptionModel = EncryptionModel.getModel(targetNode);
		if (encryptionModel != null) {
			return encryptionModel.isAccessible();
		}
		return true;
	}

	@Override
	public void load(final MapModel map, final URL url) throws FileNotFoundException, IOException, XMLParseException,
	        URISyntaxException {
		final File file = Compat.urlToFile(url);
		if (!file.exists()) {
			
			throw new FileNotFoundException(ResourceController.formatText(
			    "file_not_found", file.getPath()));
		}
		if (!file.canWrite()) {
			((MMapModel) map).setReadOnly(true);
		}
		else {
			try {
				final String lockingUser = tryToLock(map, file);
				if (lockingUser != null) {
					
					UITools.informationMessage(getController().getViewController().getFrame(), ResourceController
					    .formatText("map_locked_by_open", file.getName(),
					        lockingUser));
					((MMapModel) map).setReadOnly(true);
				}
				else {
					((MMapModel) map).setReadOnly(false);
				}
			}
			catch (final Exception e) {
				LogTool.logException(e);
				
				UITools.informationMessage(getController().getViewController().getFrame(), ResourceController
				    .formatText("locking_failed_by_open", file.getName()));
				((MMapModel) map).setReadOnly(true);
			}
		}
		final NodeModel root = loadTree(map, file);
		if (root != null) {
			((MMapModel) map).setRoot(root);
		}
		((MMapModel) map).setFile(file);
	}

	public NodeModel loadTree(final MapModel map, final File file) throws XMLParseException, IOException {
		int versionInfoLength;
		versionInfoLength = EXPECTED_START_STRINGS[0].length();
		final StringBuffer buffer = readFileStart(file, versionInfoLength);
		Reader reader = null;
		for (int i = 0; i < EXPECTED_START_STRINGS.length; i++) {
			versionInfoLength = EXPECTED_START_STRINGS[i].length();
			String mapStart = "";
			if (buffer.length() >= versionInfoLength) {
				mapStart = buffer.substring(0, versionInfoLength);
			}
			if (mapStart.startsWith(EXPECTED_START_STRINGS[i])) {
				reader = UrlManager.getActualReader(file);
				break;
			}
		}
		if (reader == null) {
			final Controller controller = getController();
			final int showResult = new OptionalDontShowMeAgainDialog(controller, "really_convert_to_current_version",
			    "confirmation", new OptionalDontShowMeAgainDialog.StandardPropertyHandler(
			        ResourceControllerProperties.RESOURCES_CONVERT_TO_CURRENT_VERSION),
			    OptionalDontShowMeAgainDialog.ONLY_OK_SELECTION_IS_STORED).show().getResult();
			if (showResult != JOptionPane.OK_OPTION) {
				reader = UrlManager.getActualReader(file);
			}
			else {
				reader = UrlManager.getUpdateReader(file, FREEPLANE_VERSION_UPDATER_XSLT);
			}
		}
		try {
			return getMapReader().createNodeTreeFromXml(map, reader);
		}
		catch (final Exception ex) {
			final String errorMessage = "Error while parsing file:" + ex;
			System.err.println(errorMessage);
			LogTool.logException(ex);
			final NodeModel result = new NodeModel(map);
			result.setText(errorMessage);
			return result;
		}
		finally {
			if (reader != null) {
				reader.close();
			}
		}
	}

	@Override
	public void loadURL(final String relative) {
		final MapModel map = getController().getMap();
		if (map.getFile() == null) {
			getController().getViewController().out("You must save the current map first!");
			final boolean result = ((MFileManager) UrlManager.getController(getModeController())).save(map);
			if (!result) {
				return;
			}
		}
		super.loadURL(relative);
	}

	public void moveNodes(final NodeModel selected, final List selecteds, final int direction) {
		((NodeUpAction) getModeController().getAction("nodeUp")).moveNodes(selected, selecteds, direction);
	}

	/**
	 * The direction is used if side left and right are present. then the next
	 * suitable place on the same side# is searched. if there is no such place,
	 * then the side is changed.
	 *
	 * @return returns the new index.
	 */
	int moveNodeToWithoutUndo(final NodeModel child, final NodeModel newParent, final int newIndex) {
		final NodeModel oldParent = child.getParentNode();
		final int oldIndex = oldParent.getIndex(child);
		oldParent.remove(child);
		newParent.insert(child, newIndex);
		fireNodeMoved(oldParent, oldIndex, newParent, child, newIndex);
		return newIndex;
	}

	@Override
	public MapModel newModel(final NodeModel root) {
		final MMapModel mindMapMapModel = new MMapModel(root, getModeController());
		fireMapCreated(mindMapMapModel);
		return mindMapMapModel;
	}

	/**
	 * Returns pMinimumLength bytes of the files content.
	 *
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private StringBuffer readFileStart(final File file, final int pMinimumLength) {
		BufferedReader in = null;
		final StringBuffer buffer = new StringBuffer();
		try {
			in = new BufferedReader(new FileReader(file));
			String str;
			while ((str = in.readLine()) != null) {
				buffer.append(str);
				if (buffer.length() >= pMinimumLength) {
					break;
				}
			}
			in.close();
		}
		catch (final Exception e) {
			LogTool.logException(e);
			return new StringBuffer();
		}
		return buffer;
	}

	@Override
	public void setFolded(final NodeModel node, final boolean folded) {
		((ToggleFoldedAction) getModeController().getAction("undoableToggleFolded")).setFolded(node, folded);
	}

	@Override
	public void toggleFolded() {
		((ToggleFoldedAction) getModeController().getAction("undoableToggleFolded")).toggleFolded();
	}

	/**
	 * Attempts to lock the map using a semaphore file
	 *
	 * @return If the map is locked, return the name of the locking user,
	 *         otherwise return null.
	 * @throws Exception
	 *             , when the locking failed for other reasons than that the
	 *             file is being edited.
	 */
	public String tryToLock(final MapModel map, final File file) throws Exception {
		final String lockingUser = ((MMapModel) map).getLockManager().tryToLock(file);
		final String lockingUserOfOldLock = ((MMapModel) map).getLockManager().popLockingUserOfOldLock();
		if (lockingUserOfOldLock != null) {
			
			UITools.informationMessage(getController().getViewController().getFrame(), ResourceController.formatText(
			    "locking_old_lock_removed", file.getName(), lockingUserOfOldLock));
		}
		if (lockingUser == null) {
			((MMapModel) map).setReadOnly(false);
		}
		return lockingUser;
	}
}
