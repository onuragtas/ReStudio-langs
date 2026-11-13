package lombok;

@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
public @interface RequiredArgsConstructor {
  String staticName() default "";
}
