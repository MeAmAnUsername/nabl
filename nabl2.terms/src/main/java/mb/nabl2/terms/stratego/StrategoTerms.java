package mb.nabl2.terms.stratego;

import static mb.nabl2.terms.build.TermBuild.B;

import java.util.LinkedList;
import java.util.Optional;

import javax.annotation.Nullable;

import org.metaborg.util.functions.Function1;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoInt;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoPlaceholder;
import org.spoofax.interpreter.terms.IStrategoReal;
import org.spoofax.interpreter.terms.IStrategoString;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.IStrategoTuple;
import org.spoofax.interpreter.terms.ITermFactory;
import org.spoofax.terms.StrategoPlaceholder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import mb.nabl2.terms.IAttachments;
import mb.nabl2.terms.IListTerm;
import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ListTerms;
import mb.nabl2.terms.Terms;
import mb.nabl2.terms.build.Attachments;
import mb.nabl2.terms.matching.VarProvider;

public class StrategoTerms {

    private final org.spoofax.interpreter.terms.ITermFactory termFactory;

    public StrategoTerms(ITermFactory termFactory) {
        this.termFactory = termFactory;
    }

    // to

    public IStrategoTerm toStratego(ITerm term) {
        return toStratego(term, false);
    }

    public IStrategoTerm toStratego(ITerm term, boolean varsToPlhdrs) {
        // @formatter:off
        IStrategoTerm strategoTerm = term.match(Terms.cases(
            appl -> {
                IStrategoTerm[] argArray = appl.getArgs().stream().map(arg -> toStratego(arg, varsToPlhdrs)).toArray(IStrategoTerm[]::new);
                return appl.getOp().equals(Terms.TUPLE_OP)
                        ? termFactory.makeTuple(argArray)
                        : termFactory.makeAppl(termFactory.makeConstructor(appl.getOp(), appl.getArity()), argArray);
            },
            list ->  toStrategoList(list, varsToPlhdrs),
            string -> termFactory.makeString(string.getValue()),
            integer -> termFactory.makeInt(integer.getValue()),
            blob -> new StrategoBlob(blob.getValue()),
            var -> {
                if (varsToPlhdrs) {
                    return termFactory.makePlaceholder(termFactory.makeTuple(termFactory.makeString(var.getResource()), termFactory.makeString(var.getName())));
                } else {
                    return termFactory.makeAppl("nabl2.Var", termFactory.makeString(var.getResource()), termFactory.makeString(var.getName()));
                }
            }
        ));
        // @formatter:on
        switch(strategoTerm.getType()) {
            case BLOB:
            case LIST:
                break;
            default:
                strategoTerm = putAttachments(strategoTerm, term.getAttachments());
        }
        return strategoTerm;
    }

    private IStrategoTerm toStrategoList(IListTerm list, boolean varsToPlhdrs) {
        final LinkedList<IStrategoTerm> terms = Lists.newLinkedList();
        final LinkedList<IAttachments> attachments = Lists.newLinkedList();
        while(list != null) {
            attachments.push(list.getAttachments());
            // @formatter:off
            list = list.match(ListTerms.<IListTerm>cases(
                cons -> {
                    terms.push(toStratego(cons.getHead(), varsToPlhdrs));
                    return cons.getTail();
                },
                nil -> {
                    return null;
                },
                var -> {
                    throw new IllegalArgumentException("Cannot convert specialized terms to Stratego.");
                }
            ));
            // @formatter:on
        }
        IStrategoList strategoList = termFactory.makeList();
        putAttachments(strategoList, attachments.pop());
        while(!terms.isEmpty()) {
            strategoList = termFactory.makeListCons(terms.pop(), strategoList);
            putAttachments(strategoList, attachments.pop());
        }
        return strategoList;
    }

    private <T extends IStrategoTerm> T putAttachments(T term, IAttachments attachments) {
        if(attachments.isEmpty()) {
            return term;
        }

        Optional<TermOrigin> origin = TermOrigin.get(attachments);
        if(origin.isPresent()) {
            origin.get().put(term);
        }

        Optional<TermIndex> index = TermIndex.get(attachments);
        if(index.isPresent()) {
            term = StrategoTermIndices.put(index.get(), term, termFactory);
        }

        @Nullable StrategoAnnotations annotations = attachments.get(StrategoAnnotations.class);
        if(annotations != null && !annotations.isEmpty()) {
            @SuppressWarnings({ "unchecked" }) T result = (T) termFactory.copyAttachments(term,
                    termFactory.annotateTerm(term, termFactory.makeList(annotations.getAnnotationList())));
            term = result;
        }

        return term;
    }

    // from

    public ITerm fromStratego(IStrategoTerm sterm) {
        return fromStratego(sterm, null);
    }

    public ITerm fromStratego(IStrategoTerm sterm, @Nullable VarProvider varProvider) {
        @Nullable IAttachments attachments = getAttachments(sterm);
        // @formatter:off
        ITerm term = match(sterm, StrategoTerms.cases(
            appl -> {
                final IStrategoTerm[] subTerms = appl.getAllSubterms();
                final ImmutableList.Builder<ITerm> args = ImmutableList.builderWithExpectedSize(subTerms.length);
                for(IStrategoTerm subTerm : subTerms) {
                    args.add(fromStratego(subTerm, varProvider));
                }
                return B.newAppl(appl.getConstructor().getName(), args.build(), attachments);
            },
            tuple -> {
                final IStrategoTerm[] subTerms = tuple.getAllSubterms();
                final ImmutableList.Builder<ITerm> args = ImmutableList.builderWithExpectedSize(subTerms.length);
                for(IStrategoTerm subTerm : subTerms) {
                    args.add(fromStratego(subTerm, varProvider));
                }
                return B.newTuple(args.build(), attachments);
            },
            list -> fromStrategoList(list, varProvider),
            integer -> B.newInt(integer.intValue(), attachments),
            real -> { throw new IllegalArgumentException("Real values are not supported."); },
            string -> B.newString(string.stringValue(), attachments),
            blob -> B.newBlob(blob.value()),
            plhdr -> {
                if (varProvider != null) {
                    return varProvider.freshWld();
                } else {
                    throw new IllegalArgumentException("Placeholders are not supported.");
                }
            }
        ));
        // @formatter:on
        return term;
    }

    private IListTerm fromStrategoList(IStrategoList list, @Nullable VarProvider varProvider) {
        final LinkedList<ITerm> terms = Lists.newLinkedList();
        final LinkedList<IAttachments> attachments = Lists.newLinkedList();
        while(!list.isEmpty()) {
            terms.add(fromStratego(list.head(), varProvider));
            attachments.push(getAttachments(list));
            list = list.tail();
        }
        attachments.add(getAttachments(list));
        return B.newList(terms, attachments);
    }

    public static IAttachments getAttachments(IStrategoTerm term) {
        final Attachments.Builder b = Attachments.Builder.of();

        TermOrigin.get(term).ifPresent(origin -> {
            b.put(TermOrigin.class, origin);
        });

        StrategoTermIndices.get(term).ifPresent(termIndex -> {
            b.put(TermIndex.class, termIndex);
        });

        final IStrategoList annos = term.getAnnotations();
        if(!annos.isEmpty()) {
            b.put(StrategoAnnotations.class, StrategoAnnotations.of(annos));
        }

        return b.build();
    }

    // matching

    public static <T> T match(IStrategoTerm term, ICases<T> cases) {
        switch(term.getType()) {
            case APPL:
                return cases.caseAppl((IStrategoAppl) term);
            case LIST:
                return cases.caseList((IStrategoList) term);
            case TUPLE:
                return cases.caseTuple((IStrategoTuple) term);
            case INT:
                return cases.caseInt((IStrategoInt) term);
            case REAL:
                return cases.caseReal((IStrategoReal) term);
            case STRING:
                return cases.caseString((IStrategoString) term);
            case BLOB:
                if(term instanceof StrategoBlob) {
                    StrategoBlob blob = (StrategoBlob) term;
                    return cases.caseBlob(blob);
                } else {
                    throw new IllegalArgumentException("Unsupported Stratego blob type " + term.getClass());
                }
            case PLACEHOLDER:
                return cases.casePlhdr((StrategoPlaceholder) term);
            default:
                throw new IllegalArgumentException("Unsupported Stratego term type " + term.getType());
        }
    }

    public static <T> ICases<T> cases(
    // @formatter:off
        Function1<IStrategoAppl, T> onAppl,
        Function1<IStrategoTuple, T> onTuple,
        Function1<IStrategoList, T> onList,
        Function1<IStrategoInt, T> onInt,
        Function1<IStrategoReal, T> onReal,
        Function1<IStrategoString, T> onString,
        Function1<StrategoBlob, T> onBlob,
        Function1<IStrategoPlaceholder, T> onPlhdr
        // @formatter:on
    ) {
        return new ICases<T>() {

            @Override public T caseAppl(IStrategoAppl term) {
                return onAppl.apply(term);
            }

            @Override public T caseTuple(IStrategoTuple term) {
                return onTuple.apply(term);
            }

            @Override public T caseList(IStrategoList term) {
                return onList.apply(term);
            }

            @Override public T caseInt(IStrategoInt term) {
                return onInt.apply(term);
            }

            @Override public T caseReal(IStrategoReal term) {
                return onReal.apply(term);
            }

            @Override public T caseString(IStrategoString term) {
                return onString.apply(term);
            }

            @Override public T caseBlob(StrategoBlob term) {
                return onBlob.apply(term);
            }

            @Override public T casePlhdr(IStrategoPlaceholder term) {
                return onPlhdr.apply(term);
            }

        };
    }

    public interface ICases<T> {

        public T caseAppl(IStrategoAppl term);

        public T caseList(IStrategoList term);

        public T caseTuple(IStrategoTuple term);

        public T caseInt(IStrategoInt term);

        public T caseReal(IStrategoReal term);

        public T caseString(IStrategoString term);

        public T caseBlob(StrategoBlob term);

        public T casePlhdr(IStrategoPlaceholder term);

    }

    @FunctionalInterface
    public interface Attacher {

        <T> void put(Class<T> clazz, T instance);

    }

}
