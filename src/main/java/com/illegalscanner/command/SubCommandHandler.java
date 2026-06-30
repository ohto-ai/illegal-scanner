package com.illegalscanner.command;

import org.bukkit.command.CommandSender;

/**
 * Interface for sub-command group handlers.
 */
public interface SubCommandHandler {
    boolean handle(CommandSender sender, String[] args);
}
