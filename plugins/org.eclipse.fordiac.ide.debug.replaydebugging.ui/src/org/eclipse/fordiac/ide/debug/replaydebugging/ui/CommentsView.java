/*******************************************************************************
 * Copyright (c) 2026 Jose Cabral
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Jose Cabral
 *     - initial API and implementation and/or initial documentation
 *******************************************************************************/

package org.eclipse.fordiac.ide.debug.replaydebugging.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.fordiac.ide.debug.replaydebugging.core.EventChange;
import org.eclipse.fordiac.ide.debug.replaydebugging.core.ReplayNavigator.EventPosition;
import org.eclipse.fordiac.ide.debug.replaydebugging.core.Timeline;
import org.eclipse.fordiac.ide.debug.replaydebugging.ui.model.TimelineModel;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnLayoutData;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

public class CommentsView extends ViewPart {

	/**
	 * Derives the display number of a timeline: root = 1, then pre-order in display
	 * order.
	 */
	private final class TimelineNumbering {

		private TimelineNumbering() {
		}

		public static Timeline rootOf(final Timeline timeline) {
			Timeline current = timeline;
			while (current.getParentTimeline() != null) {
				current = current.getParentTimeline();
			}
			return current;
		}

		/** Numbers every timeline of the tree below (and including) root. */
		public static Map<Timeline, Integer> numberTree(final Timeline root) {
			final Map<Timeline, Integer> numbers = new HashMap<>();
			number(root, numbers);
			return numbers;
		}

		private static void number(final Timeline timeline, final Map<Timeline, Integer> numbers) {
			numbers.put(timeline, Integer.valueOf(numbers.size() + 1));
			final List<Timeline> children = new ArrayList<>(timeline.getSpawnedTimelines());
			// same order as TimelineModel#getSpawnedTimelineModels
			children.sort(TimelineModel.<Timeline>getTimelineComparator(timeline, Function.identity()));
			for (final Timeline child : children) {
				number(child, numbers);
			}
		}
	}

	public static final String VIEW_ID = "org.eclipse.fordiac.ide.debug.replaydebugging.ui.CommentsView"; //$NON-NLS-1$

	/**
	 * One table row. Exactly one of timeline-only (position == null) or event
	 * comment.
	 */
	private record CommentRow(Timeline timeline, EventPosition position, String comment) {

		boolean isTimelineComment() {
			return position == null;
		}
	}

	private TableViewer viewer;

	/** Keys are either EventPosition or Timeline, mirroring CommentsHandler. */
	private final Map<Object, CommentRow> rows = new LinkedHashMap<>();

	/**
	 * Rebuilt on every refresh, since numbers shift when timelines spawn or are
	 * removed.
	 */
	private final Map<Timeline, Integer> numbers = new HashMap<>();

	/** Timelines we currently have a structure listener on. */
	private final Set<Timeline> watched = new HashSet<>();

	private final CommentsHandler.Listener commentListener = new CommentsHandler.Listener() {
		@Override
		public void eventCommentChanged(final EventPosition position, final String comment,
				final CommentsHandler.Listener.Event event) {
			update(position, comment == null ? null : new CommentRow(position.timeline(), position, comment));
		}

		@Override
		public void timelineCommentChanged(final Timeline timeline, final String comment,
				final CommentsHandler.Listener.Event event) {
			update(timeline, comment == null ? null : new CommentRow(timeline, null, comment));
		}
	};

	private final Timeline.StructureListener structureListener = new Timeline.StructureListener() {
		@Override
		public void eventAdded(final Timeline timeline) {
			// numbering is unaffected
		}

		@Override
		public void eventsRemoved(final Timeline timeline, final int removedStartEventIndex,
				final List<EventChange> removedChanges) {
			refreshAsync();
		}

		@Override
		public void timelineSpawned(final Timeline spawnedTimeline) {
			watchTree(spawnedTimeline);
			refreshAsync();
		}

		@Override
		public void timelineRemoved(final Timeline parentTimeline, final Timeline removedTimeline,
				final int spawnedAtEventNumber) {
			unwatchTree(removedTimeline);
			// Notified before the timeline is detached, so refresh later when the tree is
			// consistent.
			refreshAsync();
		}

		@Override
		public void timelineStateChanged(final Timeline timeline) {
			// numbering is unaffected
		}
	};

	@Override
	public void createPartControl(final Composite parent) {
		final Composite container = new Composite(parent, SWT.NONE);
		final TableColumnLayout layout = new TableColumnLayout();
		container.setLayout(layout);

		viewer = new TableViewer(container, SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
		viewer.getTable().setHeaderVisible(true);
		viewer.getTable().setLinesVisible(true);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.setComparator(new ViewerComparator() {
			@Override
			public int compare(final Viewer v, final Object e1, final Object e2) {
				final CommentRow a = (CommentRow) e1;
				final CommentRow b = (CommentRow) e2;
				int result = Integer.compare(numberOf(a.timeline()), numberOf(b.timeline()));
				if (result == 0) {
					// the timeline's own comment sorts before its event comments
					final int ea = a.isTimelineComment() ? -1 : a.position().eventNumber();
					final int eb = b.isTimelineComment() ? -1 : b.position().eventNumber();
					result = Integer.compare(ea, eb);
				}
				return result;
			}
		});

		createColumn(layout, "Timeline", new ColumnPixelData(90), //$NON-NLS-1$
				row -> String.valueOf(numberOf(row.timeline())));
		createColumn(layout, "Event", new ColumnPixelData(90), //$NON-NLS-1$
				row -> row.isTimelineComment() ? "" : String.valueOf(row.position().eventNumber())); //$NON-NLS-1$
		createColumn(layout, "Comment", new ColumnWeightData(1, 200, true), CommentRow::comment); //$NON-NLS-1$

		viewer.setInput(List.of());

		// Replays all existing comments on registration, then follows changes.
		CommentsHandler.getInstance().addListener(commentListener);
	}

	private void createColumn(final TableColumnLayout layout, final String title, final ColumnLayoutData data,
			final Function<CommentRow, String> text) {
		final TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(title);
		column.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(final Object element) {
				return text.apply((CommentRow) element);
			}
		});
		layout.setColumnData(column.getColumn(), data);
	}

	private void update(final Object key, final CommentRow row) {
		if (viewer == null || viewer.getControl().isDisposed()) {
			return;
		}
		if (row == null) {
			rows.remove(key);
		} else {
			rows.put(key, row);
		}
		refresh();
	}

	private void refreshAsync() {
		Display.getDefault().asyncExec(() -> {
			if (viewer != null && !viewer.getControl().isDisposed()) {
				refresh();
			}
		});
	}

	private void refresh() {
		numbers.clear();
		for (final CommentRow row : rows.values()) {
			final Timeline root = TimelineNumbering.rootOf(row.timeline());
			if (!numbers.containsKey(root)) {
				numbers.putAll(TimelineNumbering.numberTree(root));
				watchTree(root);
			}
		}
		viewer.setInput(rows.values().stream().filter(this::isStillValid).toList());
	}

	/**
	 * Hides comments whose event has been removed from its timeline in the
	 * meantime.
	 */
	private boolean isStillValid(final CommentRow row) {
		return row.isTimelineComment() || row.position().eventNumber() <= row.timeline().getMaxEventNumber();
	}

	private int numberOf(final Timeline timeline) {
		return numbers.getOrDefault(timeline, Integer.valueOf(0)).intValue();
	}

	private void watchTree(final Timeline timeline) {
		if (watched.add(timeline)) {
			timeline.addStructureListener(structureListener);
			timeline.getSpawnedTimelines().forEach(this::watchTree);
		}
	}

	private void unwatchTree(final Timeline timeline) {
		if (watched.remove(timeline)) {
			timeline.removeStructureListener(structureListener);
			timeline.getSpawnedTimelines().forEach(this::unwatchTree);
		}
	}

	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
	}

	@Override
	public void dispose() {
		CommentsHandler.getInstance().removeListener(commentListener);
		watched.forEach(t -> t.removeStructureListener(structureListener));
		watched.clear();
		super.dispose();
	}
}
