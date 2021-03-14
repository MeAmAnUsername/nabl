package mb.nabl2.terms;

import io.usethesource.capsule.Set;

public interface ITerm {

    boolean isGround();

    Set.Immutable<ITermVar> getVars();

    IAttachments getAttachments();

    ITerm withAttachments(IAttachments value);

    boolean equals(Object other, boolean compareAttachments);

    <T> T match(Cases<T> cases);

    interface Cases<T> {

        T caseAppl(IApplTerm appl);

        T caseList(IListTerm cons);

        T caseString(IStringTerm string);

        T caseInt(IIntTerm integer);

        T caseBlob(IBlobTerm integer);

        T caseVar(ITermVar var);

    }

    <T, E extends Throwable> T matchOrThrow(CheckedCases<T, E> cases) throws E;

    interface CheckedCases<T, E extends Throwable> {

        T caseAppl(IApplTerm appl) throws E;

        T caseList(IListTerm cons) throws E;

        T caseString(IStringTerm string) throws E;

        T caseInt(IIntTerm integer) throws E;

        T caseBlob(IBlobTerm integer) throws E;

        T caseVar(ITermVar var) throws E;

        default T caseLock(ITerm term) throws E {
            return term.matchOrThrow(this);
        }

    }

}
