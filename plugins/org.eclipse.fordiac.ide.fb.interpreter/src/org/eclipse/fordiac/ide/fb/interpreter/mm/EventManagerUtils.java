/*******************************************************************************
 * Copyright (c) 2021, 2022 Johannes Kepler University Linz and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Antonio Garmend�a, Bianca Wiesmayr
 *       - initial implementation and/or documentation
 *   Paul Pavlicek - cleanup
 *******************************************************************************/
package org.eclipse.fordiac.ide.fb.interpreter.mm;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fordiac.ide.fb.interpreter.DefaultRunFBType;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.CompositeFBTypeRuntime;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.EventManager;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.EventOccurrence;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.FBNetworkRuntime;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.FBRuntimeAbstract;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.FBTransaction;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.Transaction;
import org.eclipse.fordiac.ide.fb.interpreter.api.EventOccFactory;
import org.eclipse.fordiac.ide.fb.interpreter.api.TransactionFactory;
import org.eclipse.fordiac.ide.model.libraryElement.AdapterDeclaration;
import org.eclipse.fordiac.ide.model.libraryElement.Connection;
import org.eclipse.fordiac.ide.model.libraryElement.Event;
import org.eclipse.fordiac.ide.model.libraryElement.FBType;
import org.eclipse.fordiac.ide.model.libraryElement.IInterfaceElement;
import org.eclipse.fordiac.ide.model.libraryElement.LibraryElementFactory;
import org.eclipse.fordiac.ide.model.libraryElement.Value;
import org.eclipse.fordiac.ide.model.libraryElement.VarDeclaration;

public final class EventManagerUtils {

	private EventManagerUtils() {
		throw new AssertionError("This class cannot be inherited"); //$NON-NLS-1$
	}

	public static void process(final EventManager eventManager) {
		processInternal(eventManager, false);
	}

	public static void processNetwork(final EventManager eventManager) {
		processInternal(eventManager, true);
	}

	private static void processInternal(final EventManager eventManager, final boolean network) {
		DefaultRunFBType.clearCaches();
		final var transactions = eventManager.getTransactions();
		long time = eventManager.getStartTime();

		for (var i = 0; i < transactions.size(); i++) {
			final var transaction = transactions.get(i);
			if (transaction instanceof final FBTransaction fbtransaction) {
				processFbTransaction(fbtransaction, time);
				// use fb runtime in the resulting transactions
				final FBRuntimeAbstract newfbRuntime = getLatestFbRuntime(fbtransaction);

				if (network) {
					eventManager.getTransactions().addAll(createTransactionsForConnectedPins(
							fbtransaction.getOutputEventOccurrences(), (FBNetworkRuntime) newfbRuntime));
				} else if ((i + 1) < transactions.size()) {
					transactions.get(i + 1).getInputEventOccurrence().setFbRuntime(newfbRuntime);
				}
			}
			time += transaction.getDuration();
		}
	}

	public static FBRuntimeAbstract getLatestFbRuntime(final FBTransaction transaction) {
		return transaction.getInputEventOccurrence().getResultFBRuntime();
	}

	public static void processFbTransaction(final FBTransaction transaction) {
		processFbTransaction(transaction, 0);
	}

	public static void processFbTransaction(final FBTransaction transaction, final long startTime) {
		// set the input vars
		for (final var inputVar : transaction.getInputVariables()) {
			final var fbtype = transaction.getInputEventOccurrence().getFbRuntime().getModel();
			setInputVariable(inputVar, fbtype);
		}
		transaction.getInputEventOccurrence().setStartTime(startTime);
		final var result = processEventOccurrence(transaction.getInputEventOccurrence());
		transaction.getOutputEventOccurrences().addAll(result);
	}

	private static FBTransaction createNewTransaction(IInterfaceElement dest, final EventOccurrence sourceEO) {
		if (dest instanceof final AdapterDeclaration aDecl) {
			dest = InterfacePinUtils.getContainedPin(aDecl, sourceEO.getEvent().getName());
		}
		if (!(dest instanceof Event)) {
			throw new IllegalArgumentException("cannot trigger FB with pin " + dest.getName()); //$NON-NLS-1$
		}
		final EventOccurrence destEO = EventOccFactory.createFrom((Event) dest, null);

		// if the destination EO does not have a parent, it might be the outgoing
		// connection for the network inside a composite
		if (destEO.getParentFB() == null
				// Interface List -> FB Type -> FB Type Runtime
				&& dest.eContainer().eContainer().eContainer() instanceof final CompositeFBTypeRuntime rt) {
			destEO.setParentFB(rt.getFbElement());
		}
		return TransactionFactory.createFrom(destEO);
	}

	private static List<FBTransaction> processEventConns(final FBNetworkRuntime fBNetworkRuntime,
			final EventOccurrence outputEo) {
		final List<FBTransaction> generatedT = new ArrayList<>();
		if (InterfacePinUtils.isInput(outputEo.getEvent())) {
			// very first transaction (if needed) / initial trigger pin
			generatedT.add(createNewInitialTransaction(outputEo.getEvent(), fBNetworkRuntime));
		} else {
			// Find the Original Pins for all connected FBs
			for (final Connection conn : ConnectionUtils.getOutputConnections(outputEo.getEvent())) {
				generatedT.add(createNewTransaction(conn.getDestination(), outputEo));
			}
		}
		return generatedT;
	}

	private static List<Transaction> createTransactionsForConnectedPins(final EList<EventOccurrence> typeOutputEos,
			final FBNetworkRuntime fBNetworkRuntime) {
		final var transactions = new ArrayList<Transaction>();

		typeOutputEos.forEach(typeEo -> {
			// generate transactions for triggering all subsequent blocks
			final EventOccurrence networkEo = getCorrespondingNetworkEvent(typeEo, fBNetworkRuntime);
			transactions.addAll(processEventConns(fBNetworkRuntime, networkEo));
		});
		return transactions;
	}

	private static FBTransaction createNewInitialTransaction(final IInterfaceElement dest,
			final FBNetworkRuntime fBNetworkRuntime) {
		final FBNetworkRuntime copiedRt = EcoreUtil.copy(fBNetworkRuntime);
		final EventOccurrence destinationEventOccurence = EventOccFactory.createFrom((Event) dest, copiedRt);
		destinationEventOccurence.setParentFB(dest.getBlockFBNetworkElement());
		return TransactionFactory.createFrom(destinationEventOccurence);
	}

	private static EventOccurrence getCorrespondingNetworkEvent(final EventOccurrence typeEo,
			final FBNetworkRuntime fBNetworkRuntime) {
		final Event mappedEvent = InterfacePinUtils.findEventInInterface(typeEo.getParentFB(), typeEo.getEvent());
		final EventOccurrence networkEo = EventOccFactory.createFrom(mappedEvent, EcoreUtil.copy(fBNetworkRuntime));
		networkEo.setParentFB(typeEo.getParentFB());
		return networkEo;
	}

	private static List<EventOccurrence> processEventOccurrence(final EventOccurrence eo) {
		final FBRuntimeAbstract runtime = eo.getFbRuntime();
		FBRuntimeAbstract resultRuntime = eo.getResultFBRuntime();
		if (resultRuntime == null) {
			resultRuntime = EcoreUtil.copy(runtime);
			eo.setResultFBRuntime(resultRuntime);
		}
		return resultRuntime.run();
	}

	private static void setInputVariable(final VarDeclaration inputVar, final FBType type) {
		if (null != inputVar) {
			final var pin = type.getInterfaceList().getInterfaceElement(List.of(inputVar.getName()));
			if ((pin instanceof final VarDeclaration datapin) && pin.isIsInput()) {
				final Value sampledValue = LibraryElementFactory.eINSTANCE.createValue();
				datapin.setValue(sampledValue);
				sampledValue.setValue(inputVar.getValue().getValue());
			}
		}
	}

	/**
	 * sets the duration of all transactions of the event manager to the given value
	 */
	public static void setCyclicDuration(final EventManager eventManager, final long duration) {
		for (final Transaction t : eventManager.getTransactions()) {
			t.setDuration(duration);
		}
	}

	public static Resource addResourceToManager(final EventManager eventManager, final URI uri) {
		final ResourceSet reset = new ResourceSetImpl();
		final Resource res = reset.createResource(uri);
		res.getContents().add(eventManager);
		return res;
	}

	public static Resource loadResource(final URI uri) {
		final ResourceSet reset = new ResourceSetImpl();
		return reset.getResource(uri, true);
	}

	public static Resource loadResourceNotOnDemand(final URI uri) {
		final ResourceSet reset = new ResourceSetImpl();
		return reset.getResource(uri, false);
	}

}
