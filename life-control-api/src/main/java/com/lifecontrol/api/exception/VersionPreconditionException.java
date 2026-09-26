package com.lifecontrol.api.exception;

/**
 * A request asserted a version precondition about a resource whose state has moved.
 *
 * <p>Maps to <b>412 Precondition Failed</b>. It is deliberately not a {@link ConflictException}:
 * the request was well-formed and did not collide with a duplicate or an invalid state transition;
 * it simply asserted that the resource is still at a version it no longer carries. Giving the
 * precondition its own type lets a client tell a lost update apart from a duplicate.</p>
 */
public class VersionPreconditionException extends RuntimeException {

    public VersionPreconditionException(String message) {
        super(message);
    }
}
