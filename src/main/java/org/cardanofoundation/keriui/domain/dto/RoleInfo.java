package org.cardanofoundation.keriui.domain.dto;

import org.cardanofoundation.keriui.domain.Role;

/**
 * The resolved role of a Cardano wallet.
 *
 * <ul>
 *   <li>{@code roleName} – "issuer", "entity", or "user"</li>
 *   <li>{@code teRole}   – the role tier of this TE node (null for plain users)</li>
 * </ul>
 */
public record RoleInfo(String roleName, Role teRole) {}
