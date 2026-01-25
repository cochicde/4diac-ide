package org.eclipse.fordiac.ide.fb.interpreter.mm;

import org.eclipse.fordiac.ide.fb.interpreter.OpSem.CompositeFBTypeRuntime;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.EventOccurrence;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.FBNetworkRuntime;
import org.eclipse.fordiac.ide.fb.interpreter.OpSem.FBRuntimeAbstract;
import org.eclipse.fordiac.ide.model.libraryElement.FBNetworkElement;

public class RuntimeContextHandler implements AutoCloseable {

	// Represent the runtime to be used to execute a FB, along with the
	// FBNetworkRuntime where the runtime is stored.
	// As the runtime lives in the container, when assigning it to the
	// inputEventOccurrence, it will be moved out of it and changed inside the
	// execution.
	// Thus, we need to reinsert back into the container after execution
	private record ExecutionContext(FBNetworkRuntime runtimeContainer, CompositeFBTypeRuntime compositeRuntime,
			FBNetworkElement runtimeElement, FBRuntimeAbstract runtime) {
	}

	private ExecutionContext executionContext;

	public RuntimeContextHandler(final FBNetworkRuntime runtimeContainer, final EventOccurrence eventOccurrence) {

		executionContext = getExecutionContext(null, null, runtimeContainer, eventOccurrence.getParentFB());
		if (executionContext == null) {
			executionContext = new ExecutionContext(null, null, null, runtimeContainer);
		}

		eventOccurrence.setFbRuntime(executionContext.runtime);
	}

	@Override
	public void close() {
		if (executionContext.runtimeContainer != null) {
			if (executionContext.compositeRuntime != null) {
				executionContext.compositeRuntime.setNetworkRuntime((FBNetworkRuntime) executionContext.runtime);
			} else {
				executionContext.runtimeContainer.getTypeRuntimes().put(executionContext.runtimeElement,
						executionContext.runtime);
			}
		}
	}

	private ExecutionContext getExecutionContext(final FBNetworkRuntime runtimeContainer,
			final FBNetworkElement runtimeElement, final FBNetworkRuntime fbNetworkRuntime,
			final FBNetworkElement searchedFB) {

		if (fbNetworkRuntime.getTypeRuntimes().get(searchedFB) != null) {
			return new ExecutionContext(runtimeContainer, null, runtimeElement, fbNetworkRuntime);
		}

		for (final var entry : fbNetworkRuntime.getTypeRuntimes()) {
			if (entry.getValue() instanceof final FBNetworkRuntime fbNetwork) {
				final var possibleContext = getExecutionContext(fbNetworkRuntime, entry.getKey(), fbNetwork,
						searchedFB);
				if (possibleContext != null) {
					return possibleContext;
				}
			} else if (entry.getValue() instanceof final CompositeFBTypeRuntime compositeFBTypeRuntime) {
				final var possibleContext = getExecutionContext(fbNetworkRuntime, entry.getKey(),
						compositeFBTypeRuntime, searchedFB);
				if (possibleContext != null) {
					return possibleContext;
				}
			}
		}

		return null;
	}

	private ExecutionContext getExecutionContext(final FBNetworkRuntime runtimeContainer,
			final FBNetworkElement runtimeElement, final CompositeFBTypeRuntime compositeRuntime,
			final FBNetworkElement searchedFB) {

		if (compositeRuntime.getNetworkRuntime().getTypeRuntimes().get(searchedFB) != null) {
			return new ExecutionContext(runtimeContainer, compositeRuntime, runtimeElement,
					compositeRuntime.getNetworkRuntime());
		}

		for (final var entry : compositeRuntime.getNetworkRuntime().getTypeRuntimes()) {
			if (entry.getValue() instanceof final FBNetworkRuntime fbNetwork) {
				final var possibleContext = getExecutionContext(compositeRuntime.getNetworkRuntime(), entry.getKey(),
						fbNetwork, searchedFB);
				if (possibleContext != null) {
					return possibleContext;
				}
			} else if (entry.getValue() instanceof final CompositeFBTypeRuntime compositeFBTypeRuntime) {
				final var possibleContext = getExecutionContext(compositeRuntime.getNetworkRuntime(), entry.getKey(),
						compositeFBTypeRuntime, searchedFB);
				if (possibleContext != null) {
					return possibleContext;
				}
			}
		}

		return null;
	}

}
