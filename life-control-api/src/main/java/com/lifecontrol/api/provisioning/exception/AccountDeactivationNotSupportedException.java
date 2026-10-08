package com.lifecontrol.api.provisioning.exception;

/**
 * Refusal of a {@code DEACTIVATE} run: the identity provider has <b>no capability to disable an
 * account</b> (record G19), so the worker refuses the task instead of removing its roles and claims
 * while the account stays enabled (record T59).
 *
 * <p><b>Why a refusal and not the part that exists.</b> Disabling the account is the one instruction
 * {@code DEACTIVATE} cannot give: {@code IdentityProvider} declares {@code createUser},
 * {@code deleteUser} and {@code updateUser}, and {@code updateUser} replaces a whole
 * {@code UserRepresentation} rather than flipping a flag, so "turn the account off" is a capability
 * to build (W4b2b) and not a wire to connect. The alternative the tree does allow — removing the
 * roles and the {@code company_*} claims — is a <b>silent half-revocation</b>: the person keeps a
 * working account and no screen says so, which is the defect class record O4 exists to prevent. A
 * refusal the operator can read is worth more than a revocation that only looks like one.</p>
 *
 * <p><b>Why the message is authored here and not by the caller.</b> Two properties of the text are
 * load-bearing and neither survives a caller-authored string: it must <b>name the missing
 * capability</b> so the Access section explains itself instead of showing an opaque failure (record
 * T10), and it must fit {@code access_provisioning_tasks.last_error}, a {@code VARCHAR(500)}
 * ({@code V22}), so the write cannot fail on the reason for the failure. Keeping the literal in one
 * place keeps both properties provable in a unit test, and mirrors the package's other
 * self-describing refusal, {@code InvalidTaskStatusTransitionException}.</p>
 *
 * <p>It is a plain unchecked domain exception and deliberately <b>not</b> an HTTP error: there is no
 * endpoint on this path. The worker records the message as the task's {@code last_error}, which
 * leaves the row {@code FAILED}, visible in the Access section and retriable by the operator — and
 * automatically retriable once W4b2b lands the capability, because the task's kind is still
 * {@code DEACTIVATE} and the desired state is re-derived at apply time.</p>
 */
public class AccountDeactivationNotSupportedException extends RuntimeException {

    private static final String MESSAGE =
            "Access provisioning cannot execute DEACTIVATE: the identity provider has no capability to "
                    + "disable an account (G19), and removing roles and claims while the account stays enabled "
                    + "would be a silent half-revocation, so no capability was called and the task stays FAILED "
                    + "and retriable";

    public AccountDeactivationNotSupportedException() {
        super(MESSAGE);
    }
}
