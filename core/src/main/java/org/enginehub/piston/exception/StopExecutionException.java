/*
 * Piston, a flexible command management system.
 * Copyright (C) EngineHub <https://www.enginehub.org>
 * Copyright (C) Piston contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.enginehub.piston.exception;

import com.google.common.collect.ImmutableList;
import net.kyori.text.Component;
import org.enginehub.piston.Command;

import javax.annotation.Nullable;

/**
 * Signal to stop executing a command. Command reference not required.
 */
public class StopExecutionException extends CommandException {
    public StopExecutionException(Component message) {
        this(message, ImmutableList.of());
    }

    public StopExecutionException(Component message, ImmutableList<Command> commands) {
        super(message, commands);
    }
}
